RAIJI Core Monolith
Backend modular do ecossistema Raiji, organizado como um monólito modular com Java e Spring Boot.





Sumário
- Sobre o projeto
- Objetivos
- Tecnologias
- Arquitetura e módulos
- Estrutura do repositório
- Pré-requisitos
- Como executar com Docker
- Como executar localmente
- Configuração dos serviços
- Verificação da aplicação
- Comandos úteis
- Fluxo de branches e contribuição
- Boas práticas de segurança
- Solução de problemas
  Sobre o projeto
  O AVXAE-CORE-MONOLITH é o backend modular do ecossistema Raiji. O código está organizado em módulos Maven que compartilham o mesmo projeto principal e são empacotados como uma aplicação Spring Boot.
  A abordagem de monólito modular permite manter uma implantação centralizada enquanto separa responsabilidades em módulos com funções específicas. Isso facilita a manutenção, a organização do domínio e a evolução futura do sistema.
  O repositório contém módulos para componentes compartilhados, segurança, usuários e aplicação principal, além de configurações de infraestrutura para PostgreSQL e Keycloak via Docker Compose.
  Objetivos
- Organizar o backend em módulos com responsabilidades bem definidas.
- Centralizar componentes reutilizáveis em um módulo comum.
- Reunir os recursos de segurança em um módulo próprio.
- Separar as funcionalidades relacionadas a usuários.
- Disponibilizar uma aplicação Spring Boot executável.
- Simplificar a inicialização do ambiente de desenvolvimento com Docker Compose.
  Tecnologias
  Tecnologia	Uso
  Java 21	Linguagem e plataforma de execução
  Spring Boot 3.5.0	Base da aplicação backend
  Apache Maven	Gerenciamento de dependências e build multimódulo
  Spring Security	Infraestrutura de segurança da aplicação
  Keycloak	Serviço de identidade e autenticação configurado no ambiente
  PostgreSQL	Banco de dados utilizado pela infraestrutura
  Docker	Empacotamento e execução dos serviços em containers
  Docker Compose	Orquestração do ambiente local


Arquitetura e módulos
O projeto utiliza uma estrutura de monólito modular. Os módulos pertencem a um único projeto Maven pai, declarado no pom.xml raiz.
Módulos Maven
Módulo	Responsabilidade
raiji-common	Componentes e recursos compartilhados entre os módulos.
raiji-security	Componentes relacionados à segurança e integração com os mecanismos de autenticação/autorização.
raiji-users	Funcionalidades relacionadas a usuários.
raiji-app	Aplicação principal Spring Boot, responsável por reunir os módulos e iniciar o backend.


O pom.xml raiz define o projeto pai com.raiji:raiji-parent, com empacotamento pom, e declara os módulos acima. A versão do Java configurada é 21.
Infraestrutura local
O arquivo docker-compose.yml define os seguintes serviços:
Serviço	Container	Porta local	Função
PostgreSQL	raiji-postgres	5432	Persistência de dados
Keycloak	raiji-keycloak	8180	Serviço de identidade
Aplicação	raiji-app	8080	Backend Spring Boot


O Compose configura um volume persistente chamado postgres-data para os dados do PostgreSQL. A aplicação aguarda o endpoint do realm raiji do Keycloak responder antes de iniciar o processo Java no container.
Estrutura do repositório
AVXAE-CORE-MONOLITH/
├── keycloak/                 # Arquivos de container/configuração do Keycloak
├── postgres/                 # Arquivos de container/configuração do PostgreSQL
├── raiji-app/                # Aplicação principal Spring Boot
├── raiji-common/             # Componentes compartilhados
├── raiji-security/           # Componentes de segurança
├── raiji-users/              # Funcionalidades de usuários
├── Dockerfile.app            # Imagem da aplicação
├── Dockerfile.app.dockerignore
├── docker-compose.yml        # Infraestrutura de desenvolvimento
├── pom.xml                   # Projeto Maven pai e módulos
└── README.md
A estrutura interna de cada módulo pode evoluir. Consulte o código e os arquivos de configuração do próprio módulo para conhecer suas classes, entidades e interfaces disponíveis.

Pré-requisitos
Para executar o projeto, tenha instalado:
- Git para clonar o repositório.
- Java JDK 21 para executar ou compilar a aplicação fora do Docker.
- Apache Maven para executar os comandos Maven, caso não utilize o Maven Wrapper.
- Docker Desktop com Docker Compose habilitado para executar o ambiente conteinerizado.
- Uma IDE Java, como IntelliJ IDEA, é recomendada, mas não obrigatória.
  Confira as instalações:
  java -version
  mvn -version
  docker --version
  docker compose version
  Como executar com Docker
  Esta é a forma mais direta de iniciar a aplicação junto com o PostgreSQL e o Keycloak.
1. Clonar o repositório
   git clone https://github.com/RAIJI-SYSTEM/AVXAE-CORE-MONOLITH.git
   cd AVXAE-CORE-MONOLITH
2. Iniciar os serviços
   Na raiz do projeto, onde está o docker-compose.yml, execute:
   docker compose up --build
   O comando constrói as imagens e inicia os serviços. Para executá-los em segundo plano:
   docker compose up --build -d
   Na primeira execução, o download das imagens e a compilação podem levar alguns minutos.
3. Conferir os containers
   docker compose ps
   Acompanhe os logs:
   docker compose logs -f
   Para acompanhar apenas a aplicação:
   docker compose logs -f app
   Para acompanhar o Keycloak:
   docker compose logs -f keycloak
4. Endereços locais
   Com os containers iniciados, os endereços configurados são:
- Aplicação: http://localhost:8080
- Keycloak: http://localhost:8180
- PostgreSQL: localhost:5432
  A disponibilidade de uma rota HTTP específica depende dos endpoints implementados na aplicação. Consulte os controllers e as configurações do projeto para identificar as rotas de negócio disponíveis.
5. Parar o ambiente
   Para parar e remover os containers criados pelo Compose:
   docker compose down
   Para remover também o volume de dados do banco — apagando os dados persistidos localmente — use:
   docker compose down -v
   Use a segunda opção somente quando tiver certeza de que deseja excluir os dados locais.
   Como executar localmente
   A execução fora do Docker requer Java 21 e Maven. PostgreSQL e Keycloak precisam estar acessíveis e configurados conforme os perfis e propriedades da aplicação.
1. Iniciar a infraestrutura
   Se quiser manter PostgreSQL e Keycloak em containers, inicie o Compose:
   docker compose up -d postgres keycloak
2. Compilar o projeto
   Na raiz do repositório:
   mvn clean install
   Para compilar sem executar os testes:
   mvn clean package -DskipTests
   A opção -DskipTests ignora os testes e não deve substituir a execução normal dos testes antes de integrar alterações.

3. Iniciar a aplicação
   A forma exata de execução local depende das configurações e dos perfis disponíveis no módulo raiji-app. No IntelliJ IDEA, localize a classe que contém @SpringBootApplication dentro desse módulo e execute-a após configurar as propriedades necessárias para conectar ao PostgreSQL e ao Keycloak.
   Também é possível executar a aplicação com Maven, a partir da raiz, especificando o módulo correto:
   mvn -pl raiji-app -am spring-boot:run
   Se o projeto exigir um perfil específico ou variáveis de ambiente, configure-os antes de iniciar. O perfil docker é selecionado pelo docker-compose.yml para a execução dentro do container.
   Configuração dos serviços
   PostgreSQL
   O Compose configura o PostgreSQL com:
- Porta publicada: 5432
- Banco configurado: raiji_keycloak
- Usuário configurado: raiji
- Volume persistente: postgres-data
  Esses valores descrevem a configuração atual de desenvolvimento do Compose. Não utilize credenciais de desenvolvimento em ambientes compartilhados ou de produção.
  Keycloak
  O Keycloak é publicado na porta 8180 do host e utiliza o PostgreSQL como banco. A configuração do Compose define um usuário administrativo inicial e aponta a aplicação para o realm raiji.
  A configuração completa de realms, clients, roles, usuários e demais parâmetros deve ser conferida nos arquivos do diretório keycloak/ e nas configurações do módulo de segurança.
  Aplicação Spring Boot
  O container da aplicação:
- Publica a porta 8080.
- Ativa o perfil Spring docker.
- Aguarda o endpoint http://keycloak:8080/realms/raiji responder.
- Configura uma verificação de saúde usando /actuator/health.
  A verificação de saúde depende de o endpoint Actuator estar habilitado e corretamente configurado na aplicação.
  Verificação da aplicação
  Depois de iniciar o ambiente:
1. Execute docker compose ps e confira o estado dos serviços.
2. Consulte os logs da aplicação com docker compose logs -f app.
3. Verifique se o Keycloak terminou a inicialização.
4. Se o Actuator estiver disponível, acesse http://localhost:8080/actuator/health.
5. Execute os testes automatizados antes de enviar alterações para a branch compartilhada.
   Para verificar o projeto Maven:
   mvn test
   Para validar a compilação e empacotamento:
   mvn clean verify
   Comandos úteis
   Objetivo	Comando
   Iniciar todos os serviços	docker compose up -d
   Reconstruir e iniciar	docker compose up --build -d
   Ver estado dos serviços	docker compose ps
   Acompanhar todos os logs	docker compose logs -f
   Ver logs da aplicação	docker compose logs -f app
   Parar containers	docker compose down
   Compilar projeto	mvn clean install
   Executar testes	mvn test
   Validar build completo	mvn clean verify
   Listar branches locais/remotas	git branch -a
   Atualizar referências remotas	git fetch origin


Fluxo de branches e contribuição
A branch develop é utilizada como branch de integração do desenvolvimento da equipe. A branch main deve seguir as convenções acordadas pelo time para versões estáveis. Confirme as regras vigentes do repositório antes de abrir um Pull Request.
Fluxo recomendado:
1. Atualize as referências remotas.
2. Troque para develop e atualize-a.
3. Crie uma branch de trabalho para sua tarefa.
4. Implemente as alterações mantendo o escopo da tarefa.
5. Execute os testes e confira o build.
6. Faça commits com mensagens claras.
7. Envie sua branch ao GitHub e abra um Pull Request para develop.
8. Resolva comentários e conflitos antes da integração.
   Exemplo de comandos:
   git fetch origin
   git switch develop
   git pull origin develop
   git switch -c feature/nome-da-tarefa

# Depois de implementar e testar:
git add .
git commit -m "feat: descreve a alteração"
git push -u origin feature/nome-da-tarefa
Evite fazer commits diretamente em main ou develop se a equipe exigir Pull Requests. Não envie arquivos com credenciais, tokens ou configurações locais sensíveis.
Boas práticas de segurança
- Não publique senhas, tokens, chaves privadas ou segredos em commits.
- As credenciais presentes no Compose devem ser tratadas como valores de desenvolvimento; substitua-as por variáveis de ambiente ou um gerenciador de segredos antes de qualquer implantação real.
- Restrinja o acesso ao console administrativo do Keycloak.
- Utilize HTTPS e configurações adequadas de segurança fora do ambiente local.
- Conceda apenas as permissões necessárias aos usuários e serviços.
- Revise logs e mensagens de erro para evitar exposição de informações sensíveis.
- Nunca compartilhe tokens de acesso pessoal do GitHub.
  Solução de problemas
  A aplicação não inicia
  Consulte os logs:
  docker compose logs -f app
  Confirme se PostgreSQL e Keycloak estão em execução e se as configurações de conexão correspondem ao ambiente utilizado.
  O banco não fica saudável
  docker compose logs -f postgres
  docker compose ps
  Verifique se a porta 5432 não está sendo utilizada por outro serviço e se o volume está acessível.
  O Keycloak demora a iniciar
  docker compose logs -f keycloak
  O Keycloak depende do PostgreSQL. Aguarde a inicialização do banco e confira as configurações de conexão.
  A porta já está em uso
  Se 8080, 8180 ou 5432 estiver ocupada, encerre o serviço conflitante ou ajuste o mapeamento de portas no docker-compose.yml, mantendo as referências de conexão consistentes.
  O Git retorna 403 ao fazer push
  Um 403 normalmente indica falta de permissão de escrita ou autenticação inadequada. Confirme que a conta GitHub utilizada tem acesso de escrita ao repositório e que o Git está autenticando com a conta correta.
  O build Maven falha
  Confira a versão do Java:
  java -version
  mvn -version
  Utilize JDK 21 e consulte a mensagem completa do Maven para identificar o módulo e a dependência que falharam.
  Estado da documentação
  Este README documenta a estrutura Maven e os serviços definidos nos arquivos raiz do repositório. Para detalhes específicos de endpoints, regras de negócio, entidades, variáveis obrigatórias e perfis de execução, consulte o código e os arquivos de configuração de cada módulo.