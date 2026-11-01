#!/usr/bin/env python3
"""Testes estruturais da S1-03 — verificam se TUDO da issue foi implementado.

Rodam sem dependências obrigatórias (PyYAML é usado quando disponível para
validação profunda dos workflows; sem ele, os testes YAML são pulados).
"""
import os
import re
import stat
import subprocess
import unittest

try:
    import yaml  # type: ignore
    HAS_YAML = True
except ImportError:
    HAS_YAML = False

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))


def path(*parts):
    return os.path.join(ROOT, *parts)


def read(*parts):
    with open(path(*parts), encoding="utf-8") as f:
        return f.read()


if HAS_YAML:
    class _Loader(yaml.SafeLoader):
        """SafeLoader que tolera tags do Compose Spec (!override, !reset)."""

    def _any_tag(loader, tag_suffix, node):
        if isinstance(node, yaml.SequenceNode):
            return loader.construct_sequence(node, deep=True)
        if isinstance(node, yaml.MappingNode):
            return loader.construct_mapping(node, deep=True)
        return loader.construct_scalar(node)

    _Loader.add_multi_constructor("!", _any_tag)

    def load_yaml(*parts):
        return yaml.load(read(*parts), Loader=_Loader)
else:  # pragma: no cover
    def load_yaml(*parts):
        raise unittest.SkipTest("PyYAML indisponível")


def is_executable(*parts):
    return os.stat(path(*parts)).st_mode & stat.S_IXUSR != 0


REQUIRED_FILES = [
    ".github/workflows/ci.yml",
    ".github/workflows/deploy.yml",
    ".github/workflows/deploy-dev.yml",
    ".github/workflows/deploy-homolog.yml",
    ".github/workflows/rollback.yml",
    "scripts/smoke-test.sh",
    "scripts/aws/deploy.sh",
    "scripts/aws/deploy-remote.sh",
    "scripts/aws/rollback.sh",
    "scripts/aws/ecr-retag.sh",
    "scripts/aws/bootstrap-aws.sh",
    "docker-compose.aws.yml",
    "docs/DEPLOY.md",
    "infra/aws/aws-config.env",
    ".github/workflows/guardian.yml",
    "scripts/aws/guardian.sh",
    "tests/cicd/run-all.sh",
    "tests/cicd/mock_server.py",
    "tests/cicd/test_smoke_functional.sh",
    "tests/cicd/test_cicd.py",
    "tests/cicd/curl-shim/curl",
]

EXECUTABLE_FILES = [
    "scripts/smoke-test.sh",
    "scripts/aws/deploy.sh",
    "scripts/aws/deploy-remote.sh",
    "scripts/aws/rollback.sh",
    "scripts/aws/ecr-retag.sh",
    "scripts/aws/bootstrap-aws.sh",
    "scripts/aws/guardian.sh",
    "scripts/setup-cicd.sh",
    "tests/cicd/run-all.sh",
    "tests/cicd/test_smoke_functional.sh",
]


class TestArquivosExistentes(unittest.TestCase):
    def test_arquivos_criados(self):
        for f in REQUIRED_FILES:
            with self.subTest(arquivo=f):
                self.assertTrue(os.path.isfile(path(f)), f"faltando: {f}")

    def test_scripts_executaveis(self):
        for f in EXECUTABLE_FILES:
            with self.subTest(arquivo=f):
                self.assertTrue(is_executable(f), f"sem +x: {f}")

    def test_sintaxe_bash(self):
        for f in EXECUTABLE_FILES:
            with self.subTest(arquivo=f):
                r = subprocess.run(["bash", "-n", path(f)], capture_output=True)
                self.assertEqual(r.returncode, 0, r.stderr.decode())


@unittest.skipUnless(HAS_YAML, "PyYAML indisponível")
class TestCiWorkflow(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.wf = load_yaml(".github", "workflows", "ci.yml")

    def test_gatilhos_pr_e_push(self):
        on = self.wf[True] if True in self.wf else self.wf["on"]  # 'on' vira True no YAML
        self.assertIn("pull_request", on)
        self.assertIn("push", on)
        self.assertEqual(set(on["push"]["branches"]), {"main", "develop"})

    def test_concurrency_cancela_antigos(self):
        self.assertTrue(self.wf["concurrency"]["cancel-in-progress"])

    def test_matriz_dinamica_paralela(self):
        ut = self.wf["jobs"]["unit-tests"]
        self.assertEqual(ut["strategy"]["fail-fast"], False)
        self.assertIn("fromJSON", str(ut["strategy"]["matrix"]["module"]))
        self.assertIn("detect-modules", ut["needs"])

    def test_pipeline_da_issue(self):
        steps = self.wf["jobs"]["verify"]["steps"]
        blob = str(steps)
        self.assertIn("actions/checkout", blob)                       # checkout
        self.assertIn("actions/setup-java", blob)                     # setup JDK
        java_step = [s for s in steps if str(s.get("uses", "")).startswith("actions/setup-java")][0]
        self.assertEqual(str(java_step["with"]["java-version"]), "21")
        self.assertEqual(java_step["with"]["cache"], "maven")         # cache Maven
        verify_cmds = [s.get("run", "") for s in steps if "clean verify" in s.get("run", "")]
        self.assertTrue(verify_cmds, "etapa 'mvn clean verify' ausente")
        uploads = [s for s in steps if str(s.get("uses", "")).startswith("actions/upload-artifact")]
        self.assertTrue(any("jacoco" in str(s["with"]["name"]).lower() or
                            "jacoco" in str(s["with"]["path"]).lower() for s in uploads),
                        "upload do relatório JaCoCo ausente")

    def test_build_docker_paralelo(self):
        self.assertIn("docker-image", self.wf["jobs"])


@unittest.skipUnless(HAS_YAML, "PyYAML indisponível")
class TestDeployWorkflows(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.deploy = load_yaml(".github", "workflows", "deploy.yml")
        cls.dev = load_yaml(".github", "workflows", "deploy-dev.yml")
        cls.homolog = load_yaml(".github", "workflows", "deploy-homolog.yml")
        cls.rollback = load_yaml(".github", "workflows", "rollback.yml")

    @staticmethod
    def _on(wf):
        return wf[True] if True in wf else wf["on"]

    def test_dev_dispara_em_develop(self):
        self.assertEqual(self._on(self.dev)["push"]["branches"], ["develop"])

    def test_homolog_dispara_em_main(self):
        self.assertEqual(self._on(self.homolog)["push"]["branches"], ["main"])

    def test_ambos_chamam_workflow_reutilizavel(self):
        for wf, env in ((self.dev, "dev"), (self.homolog, "homolog")):
            job = list(wf["jobs"].values())[0]
            self.assertTrue(job["uses"].endswith(".github/workflows/deploy.yml"))
            self.assertEqual(job["with"]["environment"], env)

    def test_deploy_e_workflow_call_com_secrets(self):
        self.assertIn("workflow_call", self._on(self.deploy))
        secrets = self._on(self.deploy)["workflow_call"]["secrets"]
        for s in ("AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", "SSH_PRIVATE_KEY",
                  "DB_PASSWORD", "KEYCLOAK_ADMIN_CLIENT_SECRET"):
            self.assertIn(s, secrets)

    @staticmethod
    def _needs(job):
        n = job.get("needs", [])
        return [n] if isinstance(n, str) else list(n)

    def test_fluxo_build_deploy_smoke_release(self):
        jobs = self.deploy["jobs"]
        for j in ("config", "tests", "build-push", "deploy", "smoke", "release", "rollback"):
            self.assertIn(j, jobs)
        self.assertIn("tests", self._needs(jobs["build-push"]))
        self.assertIn("build-push", self._needs(jobs["deploy"]))
        self.assertIn("deploy", self._needs(jobs["smoke"]))
        # release bloqueado pelo smoke
        self.assertIn("smoke", self._needs(jobs["release"]))
        self.assertEqual(jobs["release"]["if"], "success()")

    def test_smoke_paralelo_4_checagens(self):
        smoke = self.deploy["jobs"]["smoke"]
        self.assertEqual(smoke["strategy"]["fail-fast"], False)
        self.assertEqual(smoke["strategy"]["matrix"]["check"], ["health", "realm", "token", "me"])
        run = str(smoke["steps"][-1]["run"])
        self.assertIn("smoke-test.sh", run)
        self.assertIn("--check", run)

    def test_rollback_automatico_em_falha(self):
        rb = self.deploy["jobs"]["rollback"]
        self.assertIn("failure()", rb["if"])
        self.assertIn("rollback.sh", str(rb["steps"][-1]["run"]))

    def test_imagem_vai_para_ecr(self):
        blob = str(self.deploy["jobs"]["build-push"]["steps"])
        self.assertIn("amazon-ecr-login", blob)
        self.assertIn("docker/build-push-action", blob)
        self.assertIn("ecr-retag.sh", blob)  # preserva <env>-previous

    def test_rollback_manual_dispatch(self):
        self.assertIn("workflow_dispatch", self._on(self.rollback))
        opts = self._on(self.rollback)["workflow_dispatch"]["inputs"]["environment"]["options"]
        self.assertEqual(list(opts), ["dev", "homolog"])


@unittest.skipUnless(HAS_YAML, "PyYAML indisponível")
class TestComposeAws(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.c = load_yaml("docker-compose.aws.yml")

    def test_variaveis_da_issue_no_provedor(self):
        env = self.c["services"]["app"]["environment"]
        for v in ("SPRING_PROFILES_ACTIVE", "KEYCLOAK_ADMIN_CLIENT_SECRET",
                  "DB_URL", "DB_USER", "DB_PASSWORD", "JWK_SET_URI"):
            self.assertIn(v, env, f"variável {v} ausente no ambiente do app")

    def test_app_usa_imagem_ecr(self):
        self.assertIn("${ECR_REGISTRY", self.c["services"]["app"]["image"])
        self.assertIn("${IMAGE_TAG", self.c["services"]["app"]["image"])

    def test_banco_e_app_nao_expostos(self):
        self.assertEqual(self.c["services"]["postgres"]["ports"], [])
        self.assertEqual(self.c["services"]["app"]["ports"], [])

    def test_kong_unico_ponto_publico(self):
        self.assertIn("${BASE_PORT", str(self.c["services"]["kong"]["ports"]))


class TestSmokeScript(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.src = read("scripts", "smoke-test.sh")

    def test_quatro_checagens_da_issue(self):
        self.assertIn("/actuator/health", self.src)
        self.assertIn('"status"', self.src)
        self.assertIn("/realms/$REALM", self.src)
        self.assertIn("/protocol/openid-connect/token", self.src)
        self.assertIn("/api/me", self.src)
        self.assertIn("Bearer", self.src)

    def test_modo_paralelo_e_serial(self):
        self.assertIn("--parallel", self.src)
        self.assertIn("--serial", self.src)
        self.assertIn("run_one", self.src)
        self.assertRegex(self.src, r"&\s*\n\s*pids\+=")  # jobs em background + wait

    def test_check_individual_para_matriz(self):
        self.assertIn("--check", self.src)
        for c in ("health", "realm", "token", "me"):
            self.assertIn(f"check_{c}()", self.src)

    def test_fallback_sem_curl(self):
        self.assertIn("python3", self.src)
        self.assertIn("urllib.request", self.src)

    def test_retries(self):
        self.assertIn("run_with_retries", self.src)
        self.assertIn("RETRY_DELAY", self.src)


class TestRollbackScript(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.src = read("scripts", "aws", "rollback.sh")
        cls.retag = read("scripts", "aws", "ecr-retag.sh")

    def test_retag_da_imagem_anterior(self):
        self.assertIn("ecr-retag.sh", self.src)
        self.assertIn("-previous", self.src)
        self.assertIn("-current", self.src)
        self.assertIn("batch-get-image", self.retag)
        self.assertIn("put-image", self.retag)

    def test_redeploy_e_smoke_pos_rollback(self):
        self.assertIn("compose", self.src)
        self.assertIn("up -d", self.src)
        self.assertIn("smoke-test.sh", self.src)


class TestDeployDoc(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.doc = read("docs", "DEPLOY.md")

    def test_urls_documentadas(self):
        self.assertIn("URL", self.doc)
        self.assertIn("8000", self.doc)
        self.assertIn("9000", self.doc)

    def test_variaveis_documentadas(self):
        for v in ("SPRING_PROFILES_ACTIVE", "KEYCLOAK_ADMIN_CLIENT_SECRET",
                  "DB_URL", "DB_USER", "DB_PASSWORD", "JWK_SET_URI"):
            self.assertIn(v, self.doc, f"{v} ausente em docs/DEPLOY.md")

    def test_passos_de_rollback(self):
        low = self.doc.lower()
        self.assertIn("rollback", low)
        self.assertIn("retag", low)
        self.assertIn("redeploy", low)

    def test_branches_e_ambientes(self):
        self.assertIn("develop", self.doc)
        self.assertIn("main", self.doc)
        self.assertIn("dev", self.doc)
        self.assertIn("homolog", self.doc)

    def test_limitacoes_documentadas(self):
        self.assertRegex(self.doc, r"[Ll]imitaç")

    def test_secrets_github_documentados(self):
        for s in ("AWS_ACCESS_KEY_ID", "AWS_SSH_KEY_DEV", "AWS_SSH_KEY_HOMOLOG",
                  "DB_PASSWORD_DEV", "DB_PASSWORD_HOMOLOG"):
            self.assertIn(s, self.doc)

    def test_como_rodar_os_testes(self):
        self.assertIn("tests/cicd/run-all.sh", self.doc)

    def test_config_facil_documentada(self):
        self.assertIn("aws-config.env", self.doc)
        self.assertIn("INSTANCE_TYPE_DEV", self.doc)
        self.assertIn("INSTANCE_TYPE_HOMOLOG", self.doc)
        self.assertRegex(self.doc, r"tipo de máquina|tipo da máquina")

    def test_guardian_documentado(self):
        self.assertIn("Guardian", self.doc)
        self.assertIn("guardian.yml", self.doc)

    def test_protecao_sem_versao_anterior_documentada(self):
        self.assertIn("NÃO existe versão anterior", self.doc)


@unittest.skipUnless(HAS_YAML, "PyYAML indisponível")
class TestConfigAwsFacil(unittest.TestCase):
    """Configuração AWS centralizada: 1 arquivo, lido por bootstrap e workflows."""

    @classmethod
    def setUpClass(cls):
        cls.cfg = read("infra", "aws", "aws-config.env")
        cls.deploy = load_yaml(".github", "workflows", "deploy.yml")
        cls.bootstrap = read("scripts", "aws", "bootstrap-aws.sh")

    def test_arquivo_config_com_todas_as_chaves(self):
        for key in ("AWS_REGION",
                    "INSTANCE_TYPE_DEV", "INSTANCE_TYPE_HOMOLOG",
                    "BASE_PORT_DEV", "BASE_PORT_HOMOLOG",
                    "KC_PORT_DEV", "KC_PORT_HOMOLOG"):
            with self.subTest(chave=key):
                self.assertRegex(self.cfg, rf"(?m)^{key}=", f"{key} ausente em aws-config.env")

    def test_bootstrap_le_o_config(self):
        self.assertIn("aws-config.env", self.bootstrap)
        self.assertIn("--config", self.bootstrap)
        self.assertIn("INSTANCE_TYPE_", self.bootstrap)

    def test_bootstrap_redimensiona_instancia(self):
        # mudar o tipo de máquina = editar o config e rerodar o bootstrap
        self.assertIn("modify-instance-attribute", self.bootstrap)
        self.assertIn("stop-instances", self.bootstrap)
        self.assertIn("start-instances", self.bootstrap)

    def test_deploy_tem_job_config(self):
        cfg = self.deploy["jobs"]["config"]
        self.assertIn("aws-region", cfg["outputs"])
        self.assertIn("base-port", cfg["outputs"])
        self.assertIn("infra/aws/aws-config.env", str(cfg["steps"]))

    def test_portas_e_regiao_nao_hardcoded_nos_triggers(self):
        for f in ("deploy-dev.yml", "deploy-homolog.yml"):
            wf = load_yaml(".github", "workflows", f)
            job = list(wf["jobs"].values())[0]
            with self.subTest(workflow=f):
                self.assertNotIn("base-port", job["with"])
                self.assertNotIn("aws-region", job["with"])


@unittest.skipUnless(HAS_YAML, "PyYAML indisponível")
class TestGuardian(unittest.TestCase):
    """Vigilância contínua: erro inesperado pós-deploy -> rollback + alerta."""

    @classmethod
    def setUpClass(cls):
        cls.wf = load_yaml(".github", "workflows", "guardian.yml")
        cls.sh = read("scripts", "aws", "guardian.sh")

    def test_agendado_e_manual(self):
        on = self.wf[True] if True in self.wf else self.wf["on"]
        self.assertIn("schedule", on)
        self.assertIn("cron", str(on["schedule"]))
        self.assertIn("workflow_dispatch", on)

    def test_dois_ambientes_paralelos_serializados_com_deploy(self):
        jobs = self.wf["jobs"]
        self.assertIn("guard-dev", jobs)
        self.assertIn("guard-homolog", jobs)
        # mesmo concurrency group do deploy: nunca concorre com um deploy
        self.assertEqual(jobs["guard-dev"]["concurrency"]["group"], "deploy-dev")
        self.assertEqual(jobs["guard-homolog"]["concurrency"]["group"], "deploy-homolog")

    def test_abre_issue_de_alerta(self):
        self.assertEqual(self.wf["permissions"]["issues"], "write")
        blob = str(self.wf["jobs"]["guard-dev"]["steps"])
        self.assertIn("github-script", blob)
        self.assertIn("issues.create", blob)

    def test_reconfirma_antes_de_reverter(self):
        self.assertIn("GUARDIAN_RECHECK_DELAY", self.sh)
        self.assertIn("sleep", self.sh)

    def test_so_rollback_se_houver_versao_anterior(self):
        self.assertIn("describe-images", self.sh)
        self.assertIn("-previous", self.sh)
        self.assertIn("rollback.sh", self.sh)


class TestProtecaoRelease(unittest.TestCase):
    """Versão quebrada nunca fica no ar — nem em dev, nem em homolog."""

    @classmethod
    def setUpClass(cls):
        cls.remote = read("scripts", "aws", "deploy-remote.sh")
        cls.rollback = read("scripts", "aws", "rollback.sh")
        cls.deploy_wf = read(".github", "workflows", "deploy.yml")

    def test_health_gate_bloqueia_deploy(self):
        self.assertIn("smoke-test.sh --check health", self.remote)
        self.assertIn("exit 1", self.remote)

    def test_selfrollback_para_versao_anterior(self):
        self.assertIn("PREVIOUS_TAG", self.remote)
        self.assertIn("self-rollback", self.remote)

    def test_sem_anterior_para_o_app_nao_serve_quebrado(self):
        self.assertIn("stop app", self.remote)
        self.assertIn("stop app", self.rollback)
        self.assertIn("SEM versão anterior", self.remote)

    def test_rollback_verifica_anterior_no_ecr(self):
        self.assertIn("describe-images", self.rollback)
        self.assertIn("-previous", self.rollback)

    def test_rollback_automatico_no_workflow(self):
        self.assertIn("failure()", self.deploy_wf)
        self.assertIn("rollback.sh", self.deploy_wf)


class TestSemSegredosCommitados(unittest.TestCase):
    ARQUIVOS = [
        ".github/workflows/ci.yml",
        ".github/workflows/deploy.yml",
        ".github/workflows/deploy-dev.yml",
        ".github/workflows/deploy-homolog.yml",
        ".github/workflows/rollback.yml",
        ".github/workflows/guardian.yml",
        "scripts/aws/deploy.sh",
        "scripts/aws/rollback.sh",
        "scripts/aws/guardian.sh",
        "scripts/aws/bootstrap-aws.sh",
        "docker-compose.aws.yml",
        "infra/aws/aws-config.env",
    ]
    PADROES = [
        re.compile(r"AKIA[0-9A-Z]{16}"),                    # AWS access key
        re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----"),  # chaves privadas
        re.compile(r"(?i)(aws_secret_access_key|ssh_private_key)\s*[:=]\s*['\"]?[A-Za-z0-9/+=]{20,}"),
    ]

    def test_arquivos_nao_contem_segredos(self):
        for f in self.ARQUIVOS:
            src = read(f)
            for p in self.PADROES:
                with self.subTest(arquivo=f, padrao=p.pattern):
                    self.assertIsNone(p.search(src))

    def test_segredos_vem_do_github(self):
        dev = read(".github", "workflows", "deploy-dev.yml")
        self.assertIn("secrets.AWS_SSH_KEY_DEV", dev)
        self.assertIn("secrets.DB_PASSWORD_DEV", dev)
        hom = read(".github", "workflows", "deploy-homolog.yml")
        self.assertIn("secrets.AWS_SSH_KEY_HOMOLOG", hom)
        self.assertIn("secrets.KEYCLOAK_ADMIN_CLIENT_SECRET_HOMOLOG", hom)

    def test_env_remoto_nao_commitado(self):
        gi = read(".gitignore")
        self.assertIn(".env", gi)
        self.assertIn("*.pem", gi)


if __name__ == "__main__":
    unittest.main(verbosity=2)
