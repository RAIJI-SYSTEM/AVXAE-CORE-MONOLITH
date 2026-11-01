#!/usr/bin/env bash
# =============================================================================
# bootstrap-aws.sh — provisiona (e MANTÉM) a infra AWS de cada ambiente.
#
# Idempotente: rode de novo sempre que quiser. Lê a configuração de
# infra/aws/aws-config.env (tipo de máquina, região, portas, AMI). Flags de
# CLI têm precedência sobre o arquivo.
#
#   ./scripts/aws/bootstrap-aws.sh --env dev
#   ./scripts/aws/bootstrap-aws.sh --env homolog
#   ./scripts/aws/bootstrap-aws.sh --env dev --instance-type t3.medium   # resize
#
# MUDOU O TIPO DE MÁQUINA no aws-config.env? Rerode o bootstrap: ele detecta
# a diferença, para a instância, aplica o novo tipo e religa (resize automático).
#
# Cria (uma vez): ECR `raiji-app`, security group, key pair, IAM role (pull
# ECR) e instância EC2 Ubuntu 24.04 com Docker+Compose via user-data.
# No final, imprime EXATAMENTE o que configurar no GitHub (secrets/variables).
#
# Requer: aws cli v2 configurado com permissões de EC2/IAM/ECR.
# =============================================================================
set -euo pipefail

CONFIG_FILE="infra/aws/aws-config.env"
CLI_ENV="" CLI_REGION="" CLI_INSTANCE_TYPE="" CLI_BASE_PORT="" CLI_KC_PORT="" CLI_AMI=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --env) CLI_ENV="$2"; shift 2 ;;
    --region) CLI_REGION="$2"; shift 2 ;;
    --instance-type) CLI_INSTANCE_TYPE="$2"; shift 2 ;;
    --base-port) CLI_BASE_PORT="$2"; shift 2 ;;
    --kc-port) CLI_KC_PORT="$2"; shift 2 ;;
    --ami) CLI_AMI="$2"; shift 2 ;;
    --config) CONFIG_FILE="$2"; shift 2 ;;
    *) echo "opção inválida: $1" >&2; exit 2 ;;
  esac
done

ENVIRONMENT="$CLI_ENV"
[[ "$ENVIRONMENT" =~ ^(dev|homolog)$ ]] || { echo "--env deve ser dev|homolog" >&2; exit 2; }

# --- carrega o arquivo de configuração (fonte única) ---------------------------
if [[ -f "$CONFIG_FILE" ]]; then
  set -a; . "$CONFIG_FILE"; set +a
  echo "[bootstrap] configuração carregada de $CONFIG_FILE"
else
  echo "[bootstrap] AVISO: $CONFIG_FILE não encontrado — usando padrões do script."
fi
SUFFIX="$(echo "$ENVIRONMENT" | tr '[:lower:]' '[:upper:]')"
it_var="INSTANCE_TYPE_$SUFFIX"; bp_var="BASE_PORT_$SUFFIX"; kp_var="KC_PORT_$SUFFIX"; ami_var="AMI_$SUFFIX"

REGION="${CLI_REGION:-${AWS_REGION:-us-east-1}}"
INSTANCE_TYPE="${CLI_INSTANCE_TYPE:-${!it_var:-t3.small}}"
BASE_PORT="${CLI_BASE_PORT:-${!bp_var:-8000}}"
KC_PORT="${CLI_KC_PORT:-${!kp_var:-$((BASE_PORT + 180))}}"
AMI="${CLI_AMI:-${!ami_var:-}}"

KEY_NAME="raiji-${ENVIRONMENT}-key"
REPO="raiji-app"
ROLE="raiji-ec2-ecr-${ENVIRONMENT}"
SG_NAME="raiji-${ENVIRONMENT}-sg"
log() { echo "[bootstrap:$ENVIRONMENT] $*"; }

command -v aws >/dev/null || { echo "aws cli não encontrado" >&2; exit 1; }
log "configuração: região=$REGION tipo=$INSTANCE_TYPE portas=$BASE_PORT/$KC_PORT"

# --- ECR (idempotente) ----------------------------------------------------------
if ! aws ecr describe-repositories --region "$REGION" --repository-names "$REPO" >/dev/null 2>&1; then
  log "criando repositório ECR $REPO ..."
  aws ecr create-repository --region "$REGION" --repository-name "$REPO" \
    --image-scanning-configuration scanOnPush=true >/dev/null
else
  log "ECR $REPO já existe."
fi

# --- IAM role para a EC2 puxar imagens ------------------------------------------
if ! aws iam get-role --role-name "$ROLE" >/dev/null 2>&1; then
  log "criando IAM role $ROLE ..."
  aws iam create-role --role-name "$ROLE" \
    --assume-role-policy-document '{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"ec2.amazonaws.com"},"Action":"sts:AssumeRole"}]}' >/dev/null
  aws iam put-role-policy --role-name "$ROLE" --policy-name ecr-pull --policy-document "{
    \"Version\": \"2012-10-17\",
    \"Statement\": [{
      \"Effect\": \"Allow\",
      \"Action\": [\"ecr:GetAuthorizationToken\"],
      \"Resource\": \"*\"
    }, {
      \"Effect\": \"Allow\",
      \"Action\": [\"ecr:BatchCheckLayerAvailability\",\"ecr:GetDownloadUrlForLayer\",\"ecr:BatchGetImage\"],
      \"Resource\": \"arn:aws:ecr:${REGION}:*:repository/${REPO}\"
    }]
  }"
  aws iam create-instance-profile --instance-profile-name "$ROLE" >/dev/null
  aws iam add-role-to-instance-profile --instance-profile-name "$ROLE" --role-name "$ROLE"
  sleep 10
else
  log "IAM role $ROLE já existe."
fi

# --- Security group (cria e garante as portas do config) --------------------------
VPC_ID="$(aws ec2 describe-vpcs --region "$REGION" \
  --filters Name=is-default,Values=true --query 'Vpcs[0].VpcId' --output text)"
SG_ID="$(aws ec2 describe-security-groups --region "$REGION" \
  --filters Name=group-name,Values="$SG_NAME" --query 'SecurityGroups[0].GroupId' --output text 2>/dev/null || true)"
if [[ -z "$SG_ID" || "$SG_ID" == "None" ]]; then
  log "criando security group $SG_NAME (22, $BASE_PORT, $KC_PORT) ..."
  SG_ID="$(aws ec2 create-security-group --region "$REGION" --group-name "$SG_NAME" \
    --description "Raiji $ENVIRONMENT" --vpc-id "$VPC_ID" --query GroupId --output text)"
  aws ec2 authorize-security-group-ingress --region "$REGION" --group-id "$SG_ID" \
    --protocol tcp --port 22 --cidr 0.0.0.0/0 >/dev/null
  log "AVISO: SSH (22) aberto para 0.0.0.0/0 — restrinja o CIDR em produção."
else
  log "security group já existe: $SG_ID"
fi
for P in "$BASE_PORT" "$KC_PORT"; do
  aws ec2 authorize-security-group-ingress --region "$REGION" --group-id "$SG_ID" \
    --protocol tcp --port "$P" --cidr 0.0.0.0/0 >/dev/null 2>&1 \
    && log "porta $P liberada no security group" \
    || log "porta $P já liberada"
done

# --- Key pair -----------------------------------------------------------------------
KEY_FILE="$HOME/.ssh/raiji-${ENVIRONMENT}-pem"
if ! aws ec2 describe-key-pairs --region "$REGION" --key-names "$KEY_NAME" >/dev/null 2>&1; then
  log "criando key pair $KEY_NAME -> $KEY_FILE"
  mkdir -p "$HOME/.ssh"
  aws ec2 create-key-pair --region "$REGION" --key-name "$KEY_NAME" \
    --query 'KeyMaterial' --output text > "$KEY_FILE"
  chmod 600 "$KEY_FILE"
else
  log "key pair $KEY_NAME já existe (use a .pem salva anteriormente)."
fi

# --- AMI ----------------------------------------------------------------------------
if [[ -z "$AMI" ]]; then
  AMI="$(aws ssm get-parameters --region "$REGION" \
    --names /aws/service/canonical/ubuntu/server/24.04/stable/current/amd64/hvm/ebs-gp3/ami-id \
    --query 'Parameters[0].Value' --output text)"
fi

USER_DATA='#!/bin/bash
set -euxo pipefail
apt-get update -y
apt-get install -y docker.io docker-compose-v2 awscli curl jq
systemctl enable --now docker
usermod -aG docker ubuntu
mkdir -p /opt/raiji
chown -R ubuntu:ubuntu /opt/raiji'

# --- Instância: cria, ou REDIMENSIONA se o tipo mudou no config --------------------
EXISTING="$(aws ec2 describe-instances --region "$REGION" \
  --filters "Name=tag:RaijiEnv,Values=$ENVIRONMENT" "Name=instance-state-name,Values=running,stopped,pending" \
  --query 'Reservations[0].Instances[0].InstanceId' --output text 2>/dev/null || true)"

if [[ -n "$EXISTING" && "$EXISTING" != "None" ]]; then
  CURRENT_TYPE="$(aws ec2 describe-instances --region "$REGION" --instance-ids "$EXISTING" \
    --query 'Reservations[0].Instances[0].InstanceType' --output text)"
  STATE="$(aws ec2 describe-instances --region "$REGION" --instance-ids "$EXISTING" \
    --query 'Reservations[0].Instances[0].State.Name' --output text)"
  log "instância existente: $EXISTING ($CURRENT_TYPE, $STATE)"

  if [[ "$CURRENT_TYPE" != "$INSTANCE_TYPE" ]]; then
    log "ALTERANDO tipo de máquina: $CURRENT_TYPE -> $INSTANCE_TYPE ..."
    if [[ "$STATE" == "running" || "$STATE" == "pending" ]]; then
      aws ec2 wait instance-running --region "$REGION" --instance-ids "$EXISTING" 2>/dev/null || true
      aws ec2 stop-instances --region "$REGION" --instance-ids "$EXISTING" >/dev/null
      aws ec2 wait instance-stopped --region "$REGION" --instance-ids "$EXISTING"
    fi
    aws ec2 modify-instance-attribute --region "$REGION" --instance-id "$EXISTING" \
      --instance-type "{\"Value\": \"$INSTANCE_TYPE\"}"
    aws ec2 start-instances --region "$REGION" --instance-ids "$EXISTING" >/dev/null
    aws ec2 wait instance-running --region "$REGION" --instance-ids "$EXISTING"
    log "resize concluído: $INSTANCE_TYPE"
  fi

  IP="$(aws ec2 describe-instances --region "$REGION" --instance-ids "$EXISTING" \
    --query 'Reservations[0].Instances[0].PublicIpAddress' --output text)"
else
  log "lançando instância $INSTANCE_TYPE (AMI $AMI) ..."
  ID="$(aws ec2 run-instances --region "$REGION" --image-id "$AMI" \
    --instance-type "$INSTANCE_TYPE" --key-name "$KEY_NAME" \
    --security-group-ids "$SG_ID" --iam-instance-profile Name="$ROLE" \
    --tag-specifications "ResourceType=instance,Tags=[{Key=Name,Value=raiji-$ENVIRONMENT},{Key=RaijiEnv,Value=$ENVIRONMENT}]" \
    --user-data "$USER_DATA" \
    --query 'Instances[0].InstanceId' --output text)"
  log "aguardando a instância $ID ficar running ..."
  aws ec2 wait instance-running --region "$REGION" --instance-ids "$ID"
  IP="$(aws ec2 describe-instances --region "$REGION" --instance-ids "$ID" \
    --query 'Reservations[0].Instances[0].PublicIpAddress' --output text)"
fi

SUFIXO="$SUFFIX"
cat <<FIM

============================================================
 Bootstrap $ENVIRONMENT concluído!
============================================================
 Config em uso: $CONFIG_FILE (edite-o para mudar tipo de
 máquina/região/portas e rerode este script).

 URL do ambiente: http://$IP:$BASE_PORT

 Configure no GitHub (Settings -> Secrets and variables -> Actions):

 [Variables] (aba Variables)
   AWS_EC2_HOST_$SUFIXO = $IP

 [Secrets] (aba Secrets — repositório)
   AWS_ACCESS_KEY_ID               = <IAM user p/ Actions>
   AWS_SECRET_ACCESS_KEY           = <IAM user p/ Actions>

 [Secrets] (Environment '$ENVIRONMENT' — Settings -> Environments)
   AWS_SSH_KEY_$SUFIXO             = conteúdo de $KEY_FILE
   DB_PASSWORD_$SUFIXO             = <senha forte do banco>
   KEYCLOAK_ADMIN_CLIENT_SECRET_$SUFIXO = <segredo do client raiji-admin>
   SMOKE_USERNAME_$SUFIXO          = teste            (opcional)
   SMOKE_PASSWORD_$SUFIXO          = <senha do usuário de smoke> (opcional)

 A instância nova precisa de ~2-5 min para instalar Docker (user-data).
 Teste: ssh -i $KEY_FILE ubuntu@$IP 'docker --version'
============================================================
FIM
