# Operação

Tudo o que põe o sistema no ar e o mantém de pé: a imagem, o CI que publica e faz o deploy, a VPS e
o preparo dela, a cópia cifrada de hora em hora, a restauração, a recriação da máquina, o incidente de
segurança, os pedidos sobre dados pessoais, a carga de correção e o ensaio local de tudo isso.

Nenhum dado pessoal sai do Brasil, nem na cópia cifrada. A aplicação, o PostgreSQL 17 e a cópia rodam
em contêineres numa VPS no Brasil, e a cópia vai para um bucket do S3 da AWS na região dela no Brasil
(`sa-east-1`), outro fornecedor. As imagens são construídas no GitHub Actions e publicadas no registro
de contêineres do GitHub: levam o código deste repositório, nenhum dado. O vigia recebe só um sinal
sem corpo.

| Arquivo | O que é |
|---|---|
| [`Dockerfile`](../Dockerfile) | Imagem do jar único, com a API e o aplicativo na mesma origem |
| [`.dockerignore`](../.dockerignore) e [`.dockerignore`](.dockerignore) desta pasta | O que cada build de imagem enxerga: só o que compila o jar, e só os scripts que a imagem da cópia copia |
| [`.github/workflows/ci.yml`](../.github/workflows/ci.yml) | Testes Java e do front-end, build, lint e avisos de segurança das dependências a cada push no `main` e a cada pull request, e os avisos também uma vez por semana; no push verde, a publicação das imagens; de madrugada ou pelo botão, o deploy |
| [`.github/dependabot.yml`](../.github/dependabot.yml) | Uma vez por mês, um pull request com as versões novas das Actions do CI e das imagens de terceiros, que ficam [presas](#dependências-presas) pelo SHA do commit e pelo digest |
| [`vps/`](vps/) | A VPS: o compose da produção, o proxy, o banco com o papel da aplicação sem superusuário, o preparo da máquina, o limite de conexões por origem, a configuração, o deploy, a cópia agendada, o vigia, o alerta de recusas e as tarefas do systemd |
| [`dependencias/`](dependencias/) | A verificação dos avisos de segurança das dependências e das imagens, e as exceções fundamentadas a ela |
| [`copia/`](copia/) | Imagem da cópia, a cópia, a restauração e a conferência entre as duas |
| [`exclusao/`](exclusao/) | O encerramento de uma Conta a pedido |
| [`carga/carga.mjs`](carga/carga.mjs) | Carga de correção contra uma instalação |
| [`plano/codigo.mjs`](plano/codigo.mjs) | O código de ativação de um pedido de plano, gerado depois do Pix conferido |
| [`ensaio/compose.yaml`](ensaio/compose.yaml) | A produção em miniatura, na máquina local, por cima do compose da VPS |

## Do push à produção

1. O push no `main` dispara o CI, com dois jobs em paralelo. Um roda `sh mvnw -B -ntp verify`, que
   roda os testes Java contra um PostgreSQL em contêiner, os testes e o build do front-end e gera o
   jar, e depois o lint do front-end. O outro constrói as duas imagens do commit e confere os avisos
   de segurança das dependências delas, das imagens do proxy e do banco e do front-end (ver
   [Avisos de segurança das dependências](#avisos-de-segurança-das-dependências)).
2. Com os dois jobs verdes, e com a variável do repositório `PUBLICAR_NA_VPS` valendo `true`, o job
   `publicar` constrói as duas imagens do commit e as publica no registro de contêineres do GitHub,
   em `ghcr.io/elias-costa/caixa-simples` e `ghcr.io/elias-costa/caixa-simples-copia`, com o SHA como
   tag, pelo token da própria execução. Nada vai ao ar aqui. Sem a variável, o CI testa e não publica.
3. Toda madrugada, às 3h UTC (0h no fuso America/Bahia, o começo da janela de manutenção), o job
   `implantar` pega o último commit do `main` cuja execução de push passou inteira e pede à VPS, por
   SSH, que o ponha no ar; se ele já está no ar, nada acontece. Para uma correção urgente, o botão
   **Run workflow** do CI, na aba Actions, faz o mesmo na hora.
4. Na VPS, o [script de deploy](vps/implantar.sh) confere o pedido, põe o clone do repositório no
   commit, baixa as imagens dele e troca a aplicação: a antiga para, e a nova aplica as migrations,
   valida o schema contra as entidades e passa no healthcheck. Enquanto isso, o proxy segura as
   requisições. A versão no ar só é gravada depois de a nova ficar saudável; se ela não ficar em 5
   minutos, o CI fica vermelho, e o GitHub avisa por e-mail.

A chave SSH do CI, o único segredo do repositório, só roda esse script, e o script só aceita um commit
que já está no `main`: o GitHub entrega pelo SHA também commits de forks, e quem tivesse a chave
poderia pedir um deles.

**Por que de madrugada.** As duas versões da aplicação não cabem juntas na memória da VPS, então a
troca para a aplicação por uns 40 segundos. O proxy segura cada requisição nova até a nova versão
aceitar a conexão, mas uma requisição já em andamento quando a antiga fecha a conexão volta com erro,
e o aplicativo não trata esse erro como falta de rede. No ensaio local, com a carga rodando durante
uma troca, duas de 1.362 requisições voltaram assim. De madrugada ninguém fica exposto a isso sem
querer; pelo botão, quem aperta aceita.

**Migration, compose e banco só na janela.** O script recusa entre 8h e 3h UTC o commit cuja diferença
para a versão no ar toca `src/main/resources/db/migration/`, o [`compose.yaml`](vps/compose.yaml) ou a
[imagem do banco](vps/banco/Dockerfile): a migration altera o schema com a aplicação parada, e os
outros dois podem recriar o banco ou o proxy. O botão não passa por cima disso; a execução da
madrugada leva o commit na noite seguinte. A mesma janela, das 0h às 5h no fuso America/Bahia (3h às
8h UTC), vale para troca de plano da VPS, restauração e qualquer manutenção que possa derrubar o
serviço.

**A agenda pode parar.** O GitHub desliga a agenda de um repositório público depois de 60 dias sem
atividade, e um push depois disso não a religa sozinho: o commit espera até alguém religá-la na aba
Actions, ou até o botão. A agenda também pode atrasar; um commit de migration recusado por ter passado
das 8h UTC vai na noite seguinte, porque continua sendo o último aprovado.

**Voltar atrás.** O caminho é reverter o commit no `main`: o push verde publica as imagens, e a
madrugada ou o botão põe no ar. Na VPS, como root, `/usr/local/sbin/caixa-simples-implantar <SHA>`
põe no ar um commit anterior do `main` na hora, mas a execução da madrugada volta ao último aprovado.

## Imagem

Três estágios: o build, com o Maven e o Node que o `pom.xml` fixa, em imagem com glibc; a extração
do jar em camadas, das que mudam menos para as que mudam mais; e a execução, só com o JRE e sem root,
com o usuário `caixa` de uid e gid 10001, fixos para o servidor dar a ele, e só a ele, a leitura dos
certificados Pix montados. Os testes do front-end rodam no build da imagem, porque fazem parte do
empacotamento. Os testes Java não, porque sobem um PostgreSQL em contêiner e o build não tem Docker;
quem os garante é o CI, antes da publicação. O rótulo `org.opencontainers.image.source` liga as duas
imagens a este repositório no registro, e é por ele que o deploy acha as versões antigas para apagar.
As imagens base ficam [presas por digest](#dependências-presas). O estágio de build instala o `unzip`:
sem ele, o wrapper do Maven baixaria o pacote `.tar.gz` no lugar do `.zip`, que tem outro checksum, e
recusaria a distribuição.

O build enxerga só o que o [`.dockerignore`](../.dockerignore) da raiz deixa passar: o wrapper do
Maven, o `pom.xml`, `src/main/` e `frontend/`, sem os gerados do front-end e sem configuração local,
chave, certificado ou registro de execução que alguém deixe nessas pastas. Um arquivo desses em
`src/main/resources` entraria no jar, e em `frontend/public` seria servido pela aplicação a quem o
pedisse. Os padrões começam com `**/` porque, no `.dockerignore`, um padrão sem barra só vale na raiz.
A imagem da cópia tem contexto próprio, esta pasta, e um [`.dockerignore`](.dockerignore) próprio,
que deixa entrar só os scripts e as consultas que ela copia: o ambiente do ensaio, uma chave ou o
resultado de uma carga deixados aqui não chegam ao build.

A JVM foi medida sob a carga de correção com meia CPU, no limite de 512 MB e no de 1 GB:
`-XX:+UseSerialGC -XX:MaxRAMPercentage=45 -XX:+ExitOnOutOfMemoryError`, pelo `JAVA_TOOL_OPTIONS` da
imagem. O porquê de cada valor está no próprio `Dockerfile`. Um `JAVA_TOOL_OPTIONS` definido no compose
substitui o da imagem inteiro: repita os três valores e meça de novo com a carga.

## Avisos de segurança das dependências

O CI confere os avisos de segurança publicados para o que vai ao ar: as bibliotecas Java dentro da
imagem da aplicação, os pacotes das duas imagens, as imagens do proxy e do banco que rodam na VPS e o
`package-lock.json` do front-end, inclusive as dependências de build, que geram o pacote e o service
worker servidos. A ferramenta é o Trivy, pela imagem oficial presa por digest em
[`dependencias/verificar.sh`](dependencias/verificar.sh), que constrói as imagens do checkout e as
examina. O proxy é o do digest preso no [`compose.yaml`](vps/compose.yaml) da VPS, e o banco é
construído do mesmo [Dockerfile](vps/banco/Dockerfile) que a VPS constrói: trocar o digest lá troca o
que se confere aqui. Roda a cada push no `main`, a cada pull request e uma vez por semana, num job sem
segredo nenhum. O mesmo script roda na máquina, com o Docker de pé, inclusive no Git Bash do Windows:

```bash
sh operacao/dependencias/verificar.sh
```

As bases de avisos ficam no volume `caixa-simples-trivy`. As imagens base estão presas por digest,
então a verificação examina a mesma base que a publicação constrói e que está no ar, e a execução
semanal confere a base em produção.

**O que barra a publicação:** aviso alto ou crítico que já tenha versão corrigida. Antes do portão,
cada alvo tem o relatório completo no log, com as severidades menores e os avisos ainda sem correção,
que não barram. O job `publicar` só roda com a verificação verde. A execução semanal confere o
`main` sem publicar, para um aviso novo numa dependência que não mudou aparecer sem esperar o próximo
push; a falha dela é avisada pelo GitHub a quem mexeu por último na agenda do workflow.

**Exceção fundamentada:** um aviso que barra, mas não alcança o que é publicado, ou cuja correção
ainda não pode entrar, vai para [`dependencias/excecoes.yaml`](dependencias/excecoes.yaml) com o
pacote, o porquê e a data de revisão, no máximo 90 dias à frente. Vencida a data, o aviso volta a
barrar até alguém revisar. A justificativa é pública. O purl do pacote sai no relatório da ferramenta
com `--format json`.

**Ajustes que a verificação sustenta:**

- O `pom.xml` fixa versões do Tomcat e do Jackson mais novas que as gerenciadas pelo Spring Boot, e as
  duas linhas saem quando ele gerenciar versões iguais ou mais novas.
- As imagens da cópia e do banco apagam o `gosu` que a imagem base traz. A da cópia não usa o ponto
  de entrada da base, e a do banco começa já como o usuário `postgres`, então o ponto de entrada nunca
  chama o `gosu`.
- O proxy atende só HTTP/1.1 enquanto valem as exceções dele: a versão do Go do build atual tem dois
  avisos que só o HTTP/2 alcança. Saindo um build com o Go corrigido, troca-se o digest, o HTTP/2
  volta ao [Caddyfile](vps/Caddyfile) e as exceções saem.

**O que fica de fora:** as dependências de teste do Java e os plugins do Maven, que não vão para a
imagem; os binários do PostgreSQL das imagens do banco e da cópia, compilados na base, sem registro de
pacote para a ferramenta conferir; e o Java das bases da Temurin, instalado fora do gerenciador de
pacotes: a ferramenta vê os pacotes do Alpine e não o JDK. A correção desses dois chega pela troca do
digest da base, que o Dependabot propõe. Trocar a versão da ferramenta é trocar o digest no script,
conferido em mais de um registro.

## Dependências presas

O que entra nas imagens ou roda no CI fica preso a uma versão que não pode ser trocada por baixo dela:

| O quê | Preso por | A troca |
|---|---|---|
| Actions do [CI](../.github/workflows/ci.yml) | SHA do commit, com a versão no comentário | Dependabot, uma vez por mês |
| Bases da imagem da aplicação, da cópia e do banco | Digest, nos três Dockerfiles; a cópia e o banco com o mesmo | Dependabot, uma vez por mês |
| Proxy da VPS | Digest, no [`compose.yaml`](vps/compose.yaml) | Dependabot, uma vez por mês |
| Maven | SHA-256 do zip, em `distributionSha256Sum` no [`maven-wrapper.properties`](../.mvn/wrapper/maven-wrapper.properties) | À mão |
| Trivy | Digest, no [`verificar.sh`](dependencias/verificar.sh) | À mão |
| Pacotes do front-end | `package-lock.json`, instalado com `npm ci` | À mão |

**O pull request do Dependabot.** Uma vez por mês, um por grupo: as Actions; as bases dos três
Dockerfiles; e o proxy. Só entra versão publicada há ao menos 7 dias, e, nas imagens, nunca versão
maior: outro Java ou outro PostgreSQL é decisão, e o PostgreSQL novo pede a migração dos dados. O CI
inteiro roda sobre o pull request, a verificação de avisos inclusive; verde, incorpore. A base do banco
e o proxy mudam a pasta do banco e o compose, e o deploy os leva só na janela. Um proxy novo pede
conferir as [exceções do HTTP/2](#avisos-de-segurança-das-dependências), que o pull request não tira
sozinho.

**Trocar o Maven** é trocar a URL e o `distributionSha256Sum` juntos, com o SHA-256 do zip, conferido
pelo SHA-512 que a Apache publica. Com a URL nova e o checksum antigo, o wrapper recusa a distribuição.
Ele confere o arquivo ao baixar: numa máquina nova, no build da imagem e no CI; a distribuição já
extraída numa máquina não é conferida de novo.

**As imagens deste repositório vão ao ar pelo tag do SHA**, que o registro deixa reescrever. Só o
reescreve um workflow deste repositório com permissão de publicar pacotes, que é o job `publicar`, com
as Actions presas, ou uma credencial da conta. Por isso a conta não guarda token clássico com
`write:packages`. Quem tem acesso de escrita ao repositório já poderia pôr no `main` um commit que passa
no CI, e o digest no deploy não mudaria isso; contra o roubo da conta vale a verificação em duas etapas
da [primeira publicação](#primeira-publicação).

## A VPS

Uma máquina só, com AlmaLinux 9, 1 vCPU e 2 GB, e tudo em contêineres pelo
[`compose.yaml`](vps/compose.yaml):

| Serviço | O que é | Memória |
|---|---|---|
| `proxy` | Caddy, preso por digest: termina o HTTPS, com o certificado obtido e renovado por ele, e encaminha à aplicação. O único com porta publicada, a 80 e a 443 | 128 MB |
| `app` | A aplicação, da imagem do commit no ar, sem porta publicada | 1 GB |
| `banco` | PostgreSQL 17, da imagem construída de [`vps/banco/`](vps/banco/Dockerfile), sem porta publicada, com os dados num volume | 512 MB |
| `seed` | Perfil `seed`: cria uma Conta e termina | 1 GB |
| `redefinir-senha` | Perfil `redefinir-senha`: dá ao administrador uma senha provisória, derruba os aparelhos de todos os usuários da Conta e termina | 1 GB |
| `copia` | Perfil `copia`: a cópia cifrada, pelo timer de hora em hora | 256 MB |
| `restaurar` | Perfil `restauracao`: restaura uma cópia num banco vazio, por padrão o desta máquina | |
| `encerrar` | Perfil `exclusao`: encerra uma Conta a pedido | |

Nenhum contêiner usa swap, nos limites em que a memória foi medida: a JVM com o heap em disco
travaria. Somados, os limites e o sistema passam um pouco de 2 GB, e o swap de 1 GB da máquina socorre
o sistema e o Docker num pico. A CPU não tem limite por serviço. O Docker abre a porta publicada por
fora do firewall da máquina, e por isso só o proxy publica alguma, e só em IPv4, onde o
[limite de conexões por origem](#borda) a alcança.

Na máquina, fora dos contêineres:

| Caminho ou unidade | O que é |
|---|---|
| `/opt/caixa-simples/repositorio` | Clone deste repositório público, no commit no ar; é dele que saem o compose e os scripts |
| `/etc/caixa-simples/caixa-simples.env` | A [configuração](#configuração), só do root |
| `/etc/caixa-simples/efi.env` e `efi/` | As credenciais e os certificados Pix de cada Conta |
| `/var/lib/caixa-simples/versao.env` | O commit no ar, gravado pelo deploy |
| `/usr/local/sbin/caixa-simples-implantar` | O script de deploy, cópia de [`vps/implantar.sh`](vps/implantar.sh) feita pelo preparo, fora do repositório, para um commit não trocar as regras do próprio deploy |
| `/etc/nftables/caixa-simples-limite.nft` | O [limite de conexões por origem](#borda), cópia de [`vps/limite-de-conexoes.nft`](vps/limite-de-conexoes.nft) feita pelo preparo, fora do repositório, pelo mesmo motivo |
| `caixa-simples-limite.service` | Carrega esse limite a cada boot, depois do firewalld e antes do Docker |
| `caixa-simples-copia.timer` | A [cópia](#cópia-de-hora-em-hora-cifrada), toda hora cheia, em UTC |
| `caixa-simples-vigia.timer` | O sinal de vida para o [vigia](#vigia), a cada 5 minutos |
| `caixa-simples-recusas.timer` | O [alerta de recusas](#vigia), a cada 5 minutos |
| `dnf-automatic.timer` | As atualizações de segurança, às 7h30 UTC, dentro da janela, com reinício quando pedido |

O [preparo](vps/preparar.sh) deixa o SSH só por chave e sem root, o firewall só com SSH, HTTP e HTTPS,
o limite de conexões simultâneas por origem às portas do proxy, o fuso em UTC, o swap de 1 GB, e os
logs do sistema e dos contêineres no journald, um arquivo por dia, apagados depois de 7 dias, com teto
de 1 GB: os logs podem levar o endereço de quem acessa. O usuário
`implantacao` existe só para o CI: a chave dele, com `restrict` e um comando forçado, só roda o script
de deploy, e o sudo só lhe deixa rodar esse script, sem argumento, recebendo o SHA pelo ambiente.

**Na VPS, os comandos são como root** (`sudo -i`). Todo comando do compose passa por
[`compose.sh`](vps/compose.sh), que acrescenta a configuração e a versão no ar:

```bash
cd /opt/caixa-simples/repositorio/operacao/vps
sh compose.sh ps
journalctl CONTAINER_NAME=caixa-simples-app-1 --since -1h
journalctl CONTAINER_NAME=caixa-simples-app-1 --since -1h | grep evento=
```

Os eventos de segurança da aplicação saem uma linha cada, começando por `evento=`: o login certo e o
recusado, o bloqueio do login, o token recusado, o acesso recusado por perfil, o aviso Pix recusado, a
troca e a redefinição de senha, a saída de todos os aparelhos e as ações do administrador sobre
usuários, plano, configuração e Cliente. Cada linha leva o IP de origem, os ids de Conta, de Usuário e
do alvo e o motivo da recusa numa palavra fixa, nunca nome, e-mail, contato, senha ou token. O erro
inesperado sai com o método, o caminho e a pilha, sem as mensagens das exceções.

Rodar o preparo de novo refaz cada passo sem duplicar nada. Mudou o `implantar.sh` ou o
`limite-de-conexoes.nft`, rode-o de novo: é ele que copia os dois para fora do repositório e recarrega
o limite.

## Configuração

O arquivo `/etc/caixa-simples/caixa-simples.env` é gerado uma vez por
[`criar-configuracao.sh`](vps/criar-configuracao.sh), que o preparo chama: os segredos que nascem na
máquina saem aleatórios, e o resto fica vazio, para preencher à mão. Ele nunca sobrescreve um arquivo
que já existe. Valores sem aspas e sem espaço em volta do sinal de igual. Cada contêiner recebe só as
variáveis que o compose lista para ele.

| Variável | De onde vem | Para quê |
|---|---|---|
| `CAIXA_SIMPLES_DOMINIO` | O domínio, com o registro A para a VPS | O site do proxy, o certificado e a conferência do vigia |
| `CAIXA_SIMPLES_DB_PASSWORD` | Gerada | A senha do papel `caixa_simples`, sem superusuário, para a aplicação, a cópia, a restauração e o encerramento; o banco a lê só no primeiro `initdb`, para criar o papel. Trocá-la depois exige trocá-la também dentro do banco; ver [Banco](#banco) |
| `CAIXA_SIMPLES_DB_SUPERUSUARIO_PASSWORD` | Gerada | A senha do superusuário `postgres`, que só o contêiner do banco recebe, no primeiro `initdb`. Nenhum serviço conecta com ela |
| `CAIXA_SIMPLES_JWT_SECRET` | Gerada | Assinatura dos tokens, com ao menos 32 bytes. Trocá-la obriga todo mundo a entrar de novo |
| `CAIXA_SIMPLES_PLANO_SECRET` | Gerado; guarde também fora da máquina | Confere o código de ativação de cada pedido de plano; ver [Pedido de plano](#pedido-de-plano-e-código-de-ativação). A aplicação não sobe sem ele |
| `CAIXA_SIMPLES_MENSALIDADE_CAIXA_SIMPLES` e `CAIXA_SIMPLES_MENSALIDADE_COMPLETO` | À mão, em reais, com ponto decimal; a do completo maior | Valor dos pedidos de plano. A aplicação não sobe sem elas |
| `CAIXA_SIMPLES_REMOCOES_BUCKET` e `CAIXA_SIMPLES_REMOCOES_AWS_*` | O bucket de remoções e a credencial que só grava nele | O registro mínimo antes de uma exclusão; ver [Remoção](#remoção-de-dados-e-encerramento-da-conta) |
| `CAIXA_SIMPLES_REMOCOES_DESTINO` | `s3:<bucket de remoções>/remocoes` | O encerramento grava ali, e a restauração lê dali |
| `CAIXA_SIMPLES_COPIA_CHAVE_PUBLICA` | A linha `age1...` da chave pública | A cópia é cifrada para ela |
| `CAIXA_SIMPLES_COPIA_DESTINO` e `CAIXA_SIMPLES_COPIA_DESTINO_DIARIA` | `s3:<bucket>/horaria` e `s3:<bucket>/diaria` | Os dois destinos da cópia |
| `CAIXA_SIMPLES_COPIA_HORA_DIARIA` | `6` | A hora UTC da cópia que fica 30 dias |
| `CAIXA_SIMPLES_COPIA_AWS_*` | A credencial do usuário do IAM da cópia | Grava objeto novo no bucket, e mais nada |
| `CAIXA_SIMPLES_VIGIA_COPIA`, `CAIXA_SIMPLES_VIGIA_MAQUINA` e `CAIXA_SIMPLES_VIGIA_RECUSAS` | Os endereços de sinal dos três checks | O [vigia](#vigia) |

`/etc/caixa-simples/efi.env` guarda, para cada Conta que recebe Pix, com o UUID da Conta em
maiúsculas e sem hífens, `CAIXA_SIMPLES_EFI_<UUID>_AMBIENTE`, `_CLIENT_ID`, `_CLIENT_SECRET`,
`_CHAVE_PIX`, `_CERTIFICADO_P12`, `_CERTIFICADO_SENHA`, `_WEBHOOK_ID` e `_WEBHOOK_SECRET`. O
certificado `.p12` vai em `/etc/caixa-simples/efi/<uuid da Conta>.p12`, com dono 10001, e
`_CERTIFICADO_P12` aponta para `/segredos/efi/<uuid da Conta>.p12`, onde a pasta aparece dentro da
aplicação, só para leitura.

Uma mudança na configuração vale quando o contêiner é recriado: `sh compose.sh up -d --wait` recria só
os que mudaram, e a troca da aplicação para o serviço, então vai na janela.

## Banco

O PostgreSQL tem dois papéis. `caixa_simples` é o dono do banco, sem nenhum atributo de superusuário:
a aplicação, as migrations, a cópia, a restauração e o encerramento de Conta conectam com ele, e uma
consulta injetada com ele não executa comando na máquina do banco, não lê arquivo dela e não cria
papel. O superusuário `postgres` nenhum serviço usa. Ele serve ao primeiro `initdb`, em que
[`banco/criar-papel.sh`](vps/banco/criar-papel.sh) cria o papel e o banco, e à manutenção, que entra
pelo socket local do contêiner, sem senha, e que só o root da máquina alcança:

```bash
sh compose.sh exec banco psql -U postgres -d caixa_simples
```

O script roda só com o volume do banco vazio: na instalação nova, na restauração num projeto à parte e
na recriação da máquina. Num banco que já existe, mudar uma das duas senhas na configuração não muda
nada lá dentro. Para trocar a do papel, na janela, porque recria a aplicação: `\password caixa_simples`
na manutenção acima, a mesma em `CAIXA_SIMPLES_DB_PASSWORD`, e `sh compose.sh up -d --wait`.

## Primeira publicação

1. **Contratar a VPS** com AlmaLinux 9, sem painel. Antes de qualquer Conta real, confirmar por escrito
   com o fornecedor onde a máquina fica e se há backup dele; até a resposta, só Contas de teste.
2. **Domínio:** o registro A para o IP da VPS, sem registro AAAA, porque por IPv6 o encaminhamento de
   porta do Docker não preserva o endereço de origem. Antes da primeira Conta real, o e-mail de
   atendimento no domínio, num provedor de e-mail no Brasil, com o local confirmado por escrito como o
   da VPS: ele recebe os pedidos sobre dados pessoais, o encerramento e o pedido de plano, guarda o
   registro de incidentes e entra no aviso de privacidade.
3. **Administrador.** Como root, pelo acesso inicial, um usuário com senha, para o sudo, e com a sua
   chave SSH em `~/.ssh/authorized_keys`, com dono dele e modo 600. O preparo recusa seguir sem essa
   chave, porque depois dele o SSH não aceita senha nem root.
4. **Chave do CI**, na máquina do mantenedor, fora do repositório:
   `ssh-keygen -t ed25519 -N '' -C caixa-simples-ci -f caixa-simples-ci`. A `.pub` vai para a VPS.
5. **Preparo**, como root:

   ```bash
   dnf -y install git
   git clone https://github.com/Elias-Costa/caixa-simples.git /opt/caixa-simples/repositorio
   sh /opt/caixa-simples/repositorio/operacao/vps/preparar.sh <administrador> <arquivo .pub do CI>
   ```

6. **AWS:** a chave da cópia, o bucket, o usuário do IAM e a credencial de leitura, como em
   [Cópia de hora em hora](#cópia-de-hora-em-hora-cifrada), e o bucket separado de remoções, com a
   regra de 45 dias, credencial de gravação para a aplicação e leitura para a restauração, como em
   [Remoção](#remoção-de-dados-e-encerramento-da-conta).
7. **Vigia:** os três checks, como em [Vigia](#vigia).
8. **Configuração:** preencher `/etc/caixa-simples/caixa-simples.env`.
9. **GitHub**, nas configurações do repositório:
   - o segredo `VPS_CHAVE_DE_IMPLANTACAO`, com o conteúdo da chave privada do CI;
   - a variável `VPS_ENDERECO`, com o IP ou o domínio da VPS;
   - a variável `VPS_CHAVE_DO_HOST`, com a chave pública do host no formato
     `ssh-ed25519 AAAA...`, lida na própria VPS em `/etc/ssh/ssh_host_ed25519_key.pub`, sem o
     comentário do fim;
   - a variável `PUBLICAR_NA_VPS`, com `true`.
10. **Conta e `main` no GitHub:**
    - a verificação em duas etapas na conta, por aplicativo autenticador ou chave de segurança;
    - nenhum token clássico com `write:packages` na conta: quem publica os pacotes é só o job
      `publicar` (ver [Dependências presas](#dependências-presas));
    - em **Settings**, **Rules**, **Rulesets**, duas regras para o ramo padrão. A primeira, sem
      ninguém na lista de exceção, com **Restrict deletions** e **Block force pushes**. A segunda,
      com **Require status checks to pass** e os dois jobs do CI, `Testes, build e lint` e
      `Avisos de segurança das dependências`, vindos do GitHub Actions, e o papel
      **Repository admin** na lista de exceção. São duas porque a exceção de uma regra vale para
      todas as restrições dela: o push direto do administrador continua entrando antes do CI, como
      hoje, e o force push no `main` não passa para ninguém. O deploy já só pega commit verde; os
      jobs obrigatórios valem para o pull request, o do Dependabot inclusive;
    - depois do primeiro push com o [`dependabot.yml`](../.github/dependabot.yml), conferir em
      **Insights**, **Dependency graph**, **Dependabot**, que os três ecossistemas rodaram sem erro.
11. **Imagens:** um push no `main` publica as duas imagens. Na primeira vez, conferir na página dos
    pacotes do GitHub que os dois são públicos: a VPS os baixa sem credencial.
12. **Primeiro deploy**, pelo botão **Run workflow**. Sem versão no ar ainda, ele vai a qualquer hora.
    A aplicação sobe, aplica as migrations, e o proxy obtém o certificado do domínio.
13. **Tarefas agendadas:** `systemctl start caixa-simples-copia.timer caixa-simples-vigia.timer
    caixa-simples-recusas.timer`.
14. **Memória:** `sh compose.sh exec app cat /sys/fs/cgroup/memory.max` tem de dar 1073741824, o
    limite em que a JVM foi medida.
15. **Banco:** `sh compose.sh exec banco psql -U postgres -tAc "select rolsuper, rolcreaterole,
    rolcreatedb from pg_roles where rolname = 'caixa_simples'"` tem de dar `f|f|f`, e
    `sh compose.sh exec banco psql -U postgres -tAc "select distinct usename from pg_stat_activity
    where datname = 'caixa_simples'"`, só `caixa_simples`.
16. **Borda:** conferir pelo access log, como em [Borda](#borda). Conferir também o limite de conexões:
    `systemctl is-active caixa-simples-limite` dá `active`, `nft list table inet caixa_simples_limite`
    mostra a regra, e `ss -ltn` mostra o 80 e o 443 só em `0.0.0.0`, sem escuta IPv6.
17. **Contas de teste** pelo [seed](#criar-uma-conta), com o plano completo ativado pelo
    [pedido de plano](#pedido-de-plano-e-código-de-ativação); a [carga](#carga-de-correção); e a
    [restauração](#restauração) pelos dois caminhos.

## Borda

O proxy termina o HTTPS e fala com a aplicação por HTTP, pela rede interna dos contêineres. Ele
descarta o `X-Forwarded-For` que vem do cliente e põe no lugar o endereço de quem se conectou a ele,
junto do `X-Forwarded-Proto`. No ensaio local, um `X-Forwarded-For` forjado mandado ao proxy não chegou
à aplicação.

A aplicação lê esse cabeçalho da direita para a esquerda: descarta os saltos da rede interna, e o
primeiro endereço fora dela é a origem. O que o cliente escreve à esquerda nunca é lido, mesmo que
chegasse. O `X-Forwarded-Proto` diz se a requisição chegou por HTTPS, e é o que faz a resposta levar o
`Strict-Transport-Security`.

Se aparecer um salto público entre o proxy e a aplicação, `CAIXA_SIMPLES_PROXIES_CONFIAVEIS` o
declara, sem novo build: CIDRs separados por vírgula, com a barra mesmo para um endereço só (`/32`),
porque sem nenhuma barra o valor é lido como expressão regular. Até lá, a origem passa a ser esse
salto: a falha é fechada, e nenhum endereço escrito pelo cliente vira origem.

O proxy não tem access log: a aplicação não registra cada requisição, e o endereço de quem acessa só vai
aos logs nos eventos de segurança dela, como o login e a recusa de acesso.

**Tamanho do corpo.** A aplicação recusa com 413, em Problem Details e com a explicação em português, o
corpo acima do limite da rota: 4 KB no login, 256 KB no aviso de pagamento Pix e 512 KB no resto, o
lote de sincronização incluído. O pedido que declara o tamanho é recusado pelo cabeçalho, sem ser
lido; o que chega em partes é contado enquanto é lido. O aviso Pix só tem o corpo lido depois de
conferidos o certificado e o segredo. O proxy tem um teto único de 1 MiB, o dobro do maior limite da
aplicação. Com a aplicação de pé, quem recusa é ela, ao ler o cabeçalho ou ao passar do limite da rota,
e o aplicativo mostra a explicação; o teto só age quando ela ainda não respondeu, cortando a
transferência em 1 MiB com um 502, e nenhum corpo maior chega a ela. No ensaio, os 40 MB da auditoria
pelo proxy receberam o 413 da aplicação em menos de um décimo de segundo. Ao mudar um limite em
`src/main/java/br/com/caixasimples/shared/web/LimiteDoCorpoFilter.java`, confira que o teto do
[Caddyfile](vps/Caddyfile) continua acima do maior deles. No envio da fila, o 413 é recusa como as
outras: o lote fica com a falha visível, nada sai da fila, e a rodada seguinte o reenvia.

**Conexões por origem.** Cada endereço IPv4 tem até 50 conexões abertas ao mesmo tempo com o proxy. Quem
conta é o kernel, pela tabela de [`vps/limite-de-conexoes.nft`](vps/limite-de-conexoes.nft), antes de o
proxy aceitar a conexão; a conexão nova acima do limite é descartada, o cliente a tenta de novo sozinho,
e ela entra quando outra daquela origem fechar. Sem o limite, mil conexões simultâneas de uma única
máquina esgotavam os 128 MB do proxy, que caía, e quem estava do outro lado ficava sem resposta enquanto
o ataque durasse. No ensaio, com ele, as mesmas mil não derrubaram o proxy, outra origem foi atendida
durante a rajada, e a carga de correção de três Contas atrás de um só endereço passou com seis conexões.
O navegador abre até seis por servidor, então cabem oito aparelhos atrás do mesmo endereço no pior caso.
Quem divide o endereço com um atacante, num NAT de operadora por exemplo, fica sem conexão nova enquanto
ele segura os 50 lugares: as conexões já abertas continuam, e, quando o navegador desiste da nova, o
aplicativo vende sem rede e envia a fila depois. Muitas origens juntas não são contidas: o proxy aguenta
cerca de 500 conexões simultâneas no pior caso medido.

```bash
# A regra, com o contador de conexões descartadas, e as origens com conexão aberta agora.
nft list table inet caixa_simples_limite
```

Para mudar o limite, troque o número no arquivo e rode o [preparo](vps/preparar.sh) de novo, que copia o
arquivo para `/etc/nftables/` e recarrega a tabela sem derrubar conexão.

**Conferir depois de publicar**, na janela, porque recriar a aplicação para o serviço. Ligue o access
log do Tomcat por um arquivo a mais por cima do compose, faça uma requisição de um aparelho cujo IP
público você conhece, e leia o arquivo. O primeiro campo tem de ser o seu IP; o segundo mostra o
cabeçalho como chegou. Depois recrie a aplicação sem o arquivo e apague-o.

```bash
cat > /root/borda.yaml <<'FIM'
services:
  app:
    environment:
      SERVER_TOMCAT_ACCESSLOG_ENABLED: "true"
      SERVER_TOMCAT_ACCESSLOG_DIRECTORY: /tmp
      SERVER_TOMCAT_ACCESSLOG_REQUEST_ATTRIBUTES_ENABLED: "true"
      SERVER_TOMCAT_ACCESSLOG_PATTERN: "%a %{X-Forwarded-For}i %{X-Forwarded-Proto}i %r %s"
FIM
CAIXA_SIMPLES_COMPOSE_ADICIONAL=/root/borda.yaml sh compose.sh up -d --wait app
sh compose.sh exec app sh -c 'cat /tmp/access_log.*.log'
sh compose.sh up -d --wait app && rm /root/borda.yaml
```

## Saúde da aplicação

O healthcheck do compose consulta `/actuator/health/liveness` a cada 5 segundos, depois de até 180
segundos de subida. É ele que diz ao deploy que a versão nova está de pé. O Docker não reinicia um
contêiner que fica doente, só o que termina: sem memória, a JVM termina, e o Docker a sobe de novo;
uma aplicação travada aparece no [vigia](#vigia), que confere a mesma rota pelo domínio.

Nenhuma sonda consulta o banco. Com o banco fora do ar, a instância continua de pé e as requisições
que dependem dele falham até ele voltar; uma sonda que consultasse o banco tiraria do ar uma aplicação
sem defeito, sem trazer o banco de volta. A instância só começa a responder depois das migrations,
então a sonda sem banco não deixa passar no deploy uma instância que não o alcança.

`/actuator/health` consulta o banco e serve para conferência à mão. Nenhum outro endpoint do
Actuator é exposto: qualquer outro caminho sob `/actuator` recebe a página do aplicativo, como toda
rota desconhecida fora de `/api`.

## Criar uma Conta

Conta nasce pelo seed: uma execução avulsa da imagem, com o perfil `seed` e sem servidor web, que
cria a Conta com o administrador e termina. Nunca no serviço. Rodar de novo com o mesmo e-mail não
cria nada.

**Na VPS**, como root. O nome do negócio, o e-mail e a senha são lidos pelo `read`, a senha sem eco, e
o histórico do shell guarda só os comandos: nada que identifica o administrador fica nele.

```bash
read -rp 'Nome do negócio: ' CAIXA_SIMPLES_SEED_NOME_NEGOCIO && export CAIXA_SIMPLES_SEED_NOME_NEGOCIO
read -rp 'E-mail do administrador: ' CAIXA_SIMPLES_SEED_EMAIL && export CAIXA_SIMPLES_SEED_EMAIL
read -rsp 'Senha provisória: ' CAIXA_SIMPLES_SEED_SENHA && echo && export CAIXA_SIMPLES_SEED_SENHA
sh compose.sh --profile seed run --rm seed
unset CAIXA_SIMPLES_SEED_NOME_NEGOCIO CAIXA_SIMPLES_SEED_EMAIL CAIXA_SIMPLES_SEED_SENHA
```

O log termina com `Conta <id> criada`. A senha passa pela mesma política de qualquer senha, inclusive
a verificação de vazamento, e é provisória: no primeiro acesso, o aplicativo só mostra a tela de troca,
e a senha que você escolheu deixa de valer quando o administrador escolhe a dele. Entregue-a por um
canal diferente do que leva o e-mail.

Depois da senha, o administrador só vê os termos de uso até aceitá-los em nome da Conta; o aceite
fica gravado com a versão, o instante e quem aceitou. Os operadores nunca passam por essa tela. Quando
os termos ganham uma versão nova, com a data e a constante do servidor e do aplicativo trocadas
juntas, o administrador de cada Conta aceita de novo no acesso seguinte.

**No ensaio local**, pelo mesmo serviço; ver [Ensaio local](#ensaio-local).

## Conta comprometida ou administrador sem acesso

O administrador resolve sozinho, no aplicativo, a maior parte dos casos, na tela Senha:

- **senha vazada:** troca a senha, informando a atual; todos os outros aparelhos dele saem na
  requisição seguinte;
- **aparelho perdido ou furtado, senha intacta:** Sair de todos os aparelhos, inclusive o que ele
  está usando, e entra de novo;
- **operador que esqueceu a senha, ou cujo aparelho sumiu:** o administrador redefine a senha dele na
  tela Usuários; os aparelhos do operador saem, e ele troca a senha provisória ao entrar.

Um aparelho sem rede só é alcançado quando volta a falar com o servidor. O que ele registrou sem rede
fica guardado nele e só é enviado quando o mesmo usuário entrar de novo ali.

Sobram para você dois casos, os dois com o administrador fora do aplicativo: ele esqueceu a senha e
não há outro administrador, ou alguém descobriu a senha, trocou-a e o trancou fora. Não há
recuperação por e-mail. A saída é a execução avulsa `redefinir-senha`, que pela aplicação, e não por
SQL, confere que o e-mail é de um administrador, passa a senha provisória pela política de senha,
inclusive a verificação de vazamento, grava a senha como provisória e derruba os aparelhos de
**todos** os usuários da Conta, o que tira também quem tomou a Conta e redefiniu a senha de um
operador.

1. **Confirme quem pede** por um canal que você já conhecia antes do pedido, como o telefone do
   contrato, e não pelo e-mail da Conta, que pode estar nas mãos de quem a tomou.
2. **Na VPS**, como root, com o e-mail e a senha provisória lidos pelo `read`, a senha sem eco, para
   nenhum dos dois ficar no histórico do shell:

   ```bash
   read -rp 'E-mail do administrador: ' CAIXA_SIMPLES_REDEFINICAO_EMAIL && export CAIXA_SIMPLES_REDEFINICAO_EMAIL
   read -rsp 'Senha provisória: ' CAIXA_SIMPLES_REDEFINICAO_SENHA && echo && export CAIXA_SIMPLES_REDEFINICAO_SENHA
   sh compose.sh --profile redefinir-senha run --rm redefinir-senha
   unset CAIXA_SIMPLES_REDEFINICAO_EMAIL CAIXA_SIMPLES_REDEFINICAO_SENHA
   ```

   O log termina com `Senha provisória definida na Conta <id>; acessos de <n> usuários revogados`.
   E-mail de operador, ou desconhecido, termina com erro, sem gravar nada.
3. **Entregue a senha provisória** pelo mesmo canal conferido no item 1. O aplicativo pede a troca
   no primeiro acesso.
4. **Com o administrador de volta**, peça que ele confira a tela Usuários: usuário que ele não criou
   é inativado, e operador que não consegue entrar tem a senha redefinida por ele.
5. **No log**, os [eventos de segurança](#a-vps) da Conta nos últimos 7 dias mostram de que origens
   vieram os logins, as recusas e as ações do administrador; uma origem que não é a do negócio aponta
   quem a tomou:

   ```bash
   journalctl CONTAINER_NAME=caixa-simples-app-1 --since -7d | grep 'conta=<uuid da Conta>'
   ```

Trocar `CAIXA_SIMPLES_JWT_SECRET` também derruba todos os aparelhos, mas de **todas** as Contas, e não
muda senha nenhuma; fica para a suspeita de que a chave dos tokens vazou.

Uma Conta tomada por outra pessoa é um incidente de segurança: siga também
[Incidente de segurança](#incidente-de-segurança).

## Incidente de segurança

Incidente de segurança é um evento adverso confirmado que quebra o sigilo, a integridade, a
disponibilidade ou a autenticidade de dado pessoal: o banco ou uma cópia lidos por quem não devia, uma
Conta tomada, um segredo vazado com sinal de uso, dados perdidos sem cópia. O sinal pode vir do
[alerta de recusas](#vigia), de um administrador sem acesso
([Conta comprometida](#conta-comprometida-ou-administrador-sem-acesso)), do vigia da máquina ou da
cópia, do fornecedor ou de quem achou uma falha. A suspeita ainda não confirmada passa pelos passos 1 a
3; confirmada, por todos.

O Caixa Simples tem dois papéis aqui. Dos dados dos Clientes e dos funcionários de uma Conta ele é
operador: quem os decide é a Conta, e é ela quem comunica à ANPD e a essas pessoas. Do cadastro da
Conta e do administrador (nome do negócio, nome, e-mail de acesso e resumo da senha do administrador)
e dos registros de segurança ele é controlador, e comunica ele mesmo. Os prazos contam do dia em que
se soube que o incidente alcançou dado pessoal; os da ANPD e do titular são os da Resolução CD/ANPD
nº 15/2024, contados em dobro para o agente de tratamento de pequeno porte:

| A quem | Prazo | Quando |
|---|---|---|
| O administrador de cada Conta afetada | 2 dias úteis, como os termos de uso prometem | Sempre que o incidente alcançar dado da Conta, com o que já se souber |
| A ANPD | 6 dias úteis; o complemento, em até 40 dias úteis da comunicação | Risco ou dano relevante no que é do Caixa Simples |
| Os titulares do que é do Caixa Simples | 6 dias úteis; a declaração à ANPD de que foram comunicados, em até 3 dias úteis depois | Idem |
| O registro do incidente | Feito no incidente e guardado 5 anos | Todo incidente confirmado, comunicado ou não |

### 1. Conter

- **Uma origem atacando**, achada no log como no [alerta de recusas](#vigia): bloqueie-a no limite de
  conexões, com o endereço lido pelo `read`, como root:

  ```bash
  read -rp 'Origem a bloquear: ' ORIGEM
  nft insert rule inet caixa_simples_limite encaminhamento ip saddr "$ORIGEM" ct status dnat counter drop
  ```

  Todo pacote dessa origem às portas do proxy passa a ser descartado, inclusive das conexões já
  abertas. A regra some quando o limite é recarregado, pelo preparo ou pelo `caixa-simples-limite`, ou
  quando a máquina reinicia. Para tirá-la antes, `nft -a list chain inet caixa_simples_limite
  encaminhamento` mostra o `handle` dela, e `nft delete rule inet caixa_simples_limite encaminhamento
  handle <número>` a apaga.
- **Um segredo vazado:** troque-o. A chave dos tokens derruba os aparelhos de todas as Contas; a senha
  do banco, como em [Banco](#banco); o segredo dos planos invalida os códigos ainda não aplicados
  ([Pedido de plano](#pedido-de-plano-e-código-de-ativação)); as credenciais Pix de uma Conta são
  trocadas por ela na Efí e depois em `efi.env`; a chave do CI é gerada de novo, como na
  [primeira publicação](#primeira-publicação); a chave das cópias ganha um par novo, e as cópias antigas
  expiram sozinhas.
- **Uma Conta tomada:** os passos de
  [Conta comprometida](#conta-comprometida-ou-administrador-sem-acesso).
- **Uma Conta em uso ilegal ou atacando o serviço,** que os termos permitem suspender na hora, com aviso
  ao administrador: não há execução que suspenda uma Conta só. Bloqueie as origens dela e redefina a
  senha do administrador pela execução avulsa `redefinir-senha`, que derruba os aparelhos de todos os
  usuários da Conta; os operadores ainda entram com as próprias senhas. Se isso não bastar,
  `sh compose.sh stop app` para o serviço de todas as Contas até resolver.

### 2. Preservar

O journald guarda 7 dias. Antes que o período do incidente expire, tire-o da máquina, cifrado para a
chave pública das cópias, e apague-o da VPS. Parar ou reiniciar a aplicação não apaga o journald. Como
root, na pasta `operacao/vps`, com o início em UTC, o fuso da máquina, e o seu usuário de administrador
no lugar de `<administrador>`:

```bash
chave=$(sed -n 's/^CAIXA_SIMPLES_COPIA_CHAVE_PUBLICA=//p' /etc/caixa-simples/caixa-simples.env)
arquivo=/home/<administrador>/incidente-$(date -u +%Y%m%dT%H%MZ).log.age
journalctl --since '<AAAA-MM-DD HH:MM>' -o short-iso-precise \
  | sh compose.sh --profile copia run --rm -T --no-deps --entrypoint age copia -r "$chave" > "$arquivo"
chown <administrador> "$arquivo"
```

São os logs da máquina inteira, com os acessos por SSH, e não só os da aplicação. Do seu computador,
`scp <administrador>@<VPS>:incidente-*.log.age .`, e depois, na VPS, `rm "$arquivo"`. Para ler,
`age -d -i <arquivo da chave privada> <arquivo>`, ou pela imagem da cópia, como a chave foi gerada. O
arquivo fica com você até o incidente fechar, ou, se ele foi comunicado à ANPD, até o processo dela
terminar, e então é apagado; o registro do incidente guarda o resumo.

### 3. Avaliar

As Contas e as origens envolvidas estão nos [eventos de segurança](#a-vps):

```bash
journalctl CONTAINER_NAME=caixa-simples-app-1 --since -7d | grep evento= | grep -oE 'conta=[^ ]+' | sort | uniq -c
journalctl CONTAINER_NAME=caixa-simples-app-1 --since -7d | grep 'conta=<uuid da Conta>' | grep -oE 'origem=[^ ]+' | sort | uniq -c
```

O número de titulares de cada Conta, pela manutenção do banco: os Clientes não removidos e os usuários,
inclusive os inativos, que guardam o nome:

```bash
sh compose.sh exec -T banco psql -U postgres -d caixa_simples <<'FIM'
select c.id,
       (select count(*) from cliente cl where cl.conta_id = c.id and cl.removido_em is null) as clientes,
       (select count(*) from usuario u where u.conta_id = c.id) as usuarios
from conta c order by c.id;
FIM
```

Há risco ou dano relevante quando o incidente pode afetar significativamente os titulares, impedindo o
uso do serviço ou causando dano material ou moral, fraude ou roubo de identidade, e envolve ao menos um
destes: dado de autenticação, como a credencial, que é o e-mail e o resumo da senha; dado financeiro,
como a chave Pix e as credenciais da Efí de uma Conta; dado em larga escala; dado sensível, sob sigilo ou
de criança, adolescente ou idoso. Sem risco relevante, nada vai à ANPD nem aos titulares do que é do
Caixa Simples, e o motivo vai ao registro. O aviso à Conta afetada sai do mesmo jeito: é ela quem avalia
o risco dos seus Clientes e funcionários.

### 4. Avisar cada Conta afetada

Pelo e-mail de atendimento, a todos os administradores ativos da Conta, pelo modelo A, com o que já se
souber; o que vier depois segue na mesma conversa, e o Caixa Simples responde o que a Conta precisar
para a comunicação dela. Os e-mails dos administradores, pela manutenção do banco:

```bash
read -rp 'Conta: ' CONTA
sh compose.sh exec -T banco psql -U postgres -d caixa_simples -v conta="$CONTA" <<'FIM'
select cr.email from usuario u join credencial cr on cr.usuario_id = u.id
where u.conta_id = :'conta' and u.perfil = 'ADMIN' and u.ativo;
FIM
```

### 5. Comunicar o que é do Caixa Simples

Com risco ou dano relevante no cadastro da Conta e do administrador ou nos registros de segurança:

- **à ANPD**, pelo formulário eletrônico de comunicação de incidente, no site dela, declarando que o
  Caixa Simples é agente de tratamento de pequeno porte. O formulário pede: a natureza e a categoria
  dos dados; o número de titulares afetados e o total de titulares das atividades afetadas; as medidas
  de segurança de antes e de depois; os riscos e os impactos; o motivo da demora, se houver; as medidas
  para reverter ou mitigar; a data do incidente e a do conhecimento; a identificação do controlador e
  de quem o representa; a descrição e a causa. O que faltar vai como complemento;
- **a cada administrador alcançado**, pelo e-mail dele, pelo modelo B, em linguagem simples; depois, a
  declaração à ANPD de que eles foram comunicados, e por qual meio;
- **a quem só aparece no log por um endereço**, sem nome nem contato, e por isso não identificável: o
  aviso no aplicativo, na tela de login, por ao menos três meses.

### 6. Registrar

Todo incidente confirmado, comunicado ou não, vira uma mensagem no e-mail de atendimento, numa pasta
de incidentes, pelo modelo C; as comunicações à Conta, aos titulares e à ANPD seguem na mesma conversa.
O registro fica cinco anos e depois é apagado. Enquanto a caixa de atendimento não existe, só há Contas
de teste, e o registro fica com o mantenedor, levado para a caixa quando ela existir.

### Modelos

**A. Aviso à Conta afetada**, a todos os administradores ativos dela:

```text
Assunto: Incidente de segurança com dados da sua Conta no Caixa Simples

Em <data>, soubemos de um incidente de segurança que alcançou dados da Conta <nome do negócio>.

O que aconteceu: <descrição e causa, se conhecida>. Quando: <data do incidente, se conhecida>.
Que dados: <por exemplo, nomes e contatos de Clientes, nomes e e-mails de usuários, vendas>.
Quantas pessoas: <Clientes e usuários da Conta alcançados>.
Riscos para essas pessoas: <o que pode acontecer>.
Como os dados estavam protegidos e o que já fizemos: <medidas de antes e de depois>.
O que a Conta pode fazer: <por exemplo, trocar senhas, conferir a tela Usuários, avisar Clientes>.

A Conta decide sobre os dados dos seus Clientes e funcionários e avalia se comunica o incidente à
ANPD e a eles. O Caixa Simples, <identificação>, trata esses dados em nome da Conta e responde nesta
conversa o que ela precisar para isso. Este aviso traz o que já sabemos; o resto vem aqui.
```

**B. Comunicação ao administrador titular:**

```text
Assunto: Incidente de segurança com o seu cadastro no Caixa Simples

Em <data do conhecimento>, soubemos de um incidente de segurança que alcançou o seu cadastro de
administrador no Caixa Simples.

Que dados: <por exemplo, o nome, o e-mail de acesso, o resumo protegido da senha, o nome do negócio>.
Como estavam protegidos: <por exemplo, a senha guardada só como resumo, a conexão cifrada>.
Riscos para você: <o que pode acontecer>.
O que já fizemos e o que ainda faremos: <medidas>.
O que recomendamos: <por exemplo, trocar a senha aqui e onde ela se repita, desconfiar de mensagem
que peça a senha>.
<Só se passou do prazo: por que esta comunicação demorou.>

Para qualquer dúvida, responda este e-mail.
```

**C. Registro do incidente:**

```text
Assunto: Incidente <AAAA-MM-DD>, <resumo em poucas palavras>

1. Data em que se soube do incidente:
2. Circunstâncias: o que aconteceu, como foi notado, a causa, se conhecida:
3. Natureza e categoria dos dados afetados:
4. Número de titulares afetados, por Conta e no que é do Caixa Simples:
5. Avaliação do risco e dos possíveis danos aos titulares:
6. Medidas de correção e de mitigação:
7. Comunicações feitas, a cada Conta, à ANPD e aos titulares, com a data e o meio
   (as mensagens seguem nesta conversa):
8. Sem comunicação à ANPD e aos titulares, o motivo:
Trecho do log preservado: onde está e quando foi apagado:
```

## Pedido de plano e código de ativação

Toda Conta nasce no plano gratuito. O administrador pede a adesão, o upgrade ou a renovação na tela
Plano do aplicativo, manda o texto do pedido pelo canal de atendimento, o e-mail de atendimento do
domínio, e faz o Pix do valor. O
pedido sozinho não muda nada: o plano só vale com o código, e o código só sai depois do Pix
conferido no extrato.

1. Conferir no extrato o Pix com o valor do pedido. O upgrade cobra a diferença das mensalidades
   pelos dias que faltam até o vencimento, calculada com o crédito no dia do pedido: se o crédito
   caiu em outro dia, recalcular e acertar a diferença pelo canal antes do código.
2. Gerar o código com o id, o plano e o valor do pedido:

   ```bash
   CAIXA_SIMPLES_PLANO_SECRET=<segredo> node operacao/plano/codigo.mjs <id do pedido> <plano> <valor>
   ```

   O plano vai como no texto (`Caixa Simples`, `Completo`) ou como no sistema; o valor, como no
   texto (`"R$ 123,45"`, entre aspas por causa do espaço, ou `123,45`) ou como no sistema
   (`123.45`). O valor é o do pedido que o Pix cobre: no upgrade com crédito em outro dia, o do
   pedido, depois de acertada a diferença, e não o recalculado. O script só calcula: não toca o
   banco nem a Conta, e o mesmo pedido sempre dá o mesmo código.
3. Mandar o código ao administrador, que o aplica na tela Plano.

O código vale para um pedido só, com o plano e o valor gravados nele. Se a Conta pediu de novo
antes de aplicar, o pedido anterior foi substituído e o código dele é recusado: gere o do pedido
mais recente. Aplicar o mesmo código duas vezes não ativa duas vezes.

A aplicação confere o código com o plano e o valor que gravou no pedido, não com os do texto que
chegou pelo canal. Um texto alterado, com um valor menor, leva a um código recusado, e o plano não
muda. Se o administrador disser que o código foi recusado, compare com ele o texto da tela Plano e
o Pix recebido, ou peça um pedido novo, que substitui o anterior, e gere o código dele depois de
conferir o Pix do valor novo.

O segredo é o `CAIXA_SIMPLES_PLANO_SECRET` da [configuração](#configuração), gerado com ela e guardado
também fora da máquina, com o mantenedor, porque o script precisa dele. Trocá-lo invalida os códigos
ainda não aplicados. Mudar uma mensalidade vale para os pedidos seguintes, depois de recriar a
aplicação, e o pedido aberto mantém o valor do dia em que foi feito.

O plano pago vence todo mês no dia da adesão. O administrador vê o aviso sete dias antes; depois do
vencimento, os recursos pagos seguem por sete dias e param no oitavo, até a renovação. Venda, caixa
e cadastro nunca param.

## Cópia de hora em hora cifrada

A cada hora cheia, em UTC, o timer `caixa-simples-copia` roda [`copia-agendada.sh`](vps/copia-agendada.sh),
que roda o perfil `copia` com a imagem da versão no ar. A cópia:

1. lê a conferência: a contagem de linhas de cada tabela e os totais por dia das Vendas concluídas e
   dos movimentos de caixa, em [`copia/conferencia.sql`](copia/conferencia.sql);
2. roda o `pg_dump` direto para o `age`, cifrado para a chave pública do mantenedor. O dump em claro
   nunca chega ao disco, e a máquina não consegue decifrar o que guardou;
3. lê a conferência de novo. Se as duas leituras diferem, houve escrita no meio, e a cópia é
   refeita, até três vezes; depois da terceira, ela falha sem enviar nada;
4. envia `caixa-simples-<instante UTC>.dump.age` e, por último,
   `caixa-simples-<instante UTC>.conferencia.age`, também cifrada. A cópia só está completa com o
   par.

Depois, o script manda ao vigia o sinal do check da cópia, ou o de falha, que avisa o mantenedor de
que aquela hora ficou sem cópia. O sinal não leva o log da cópia nem nada das Contas. Uma hora perdida
com a máquina desligada fica perdida, e o vigia avisa da falta.

A cópia que começa na hora `CAIXA_SIMPLES_COPIA_HORA_DIARIA`, 6h UTC (3h no fuso America/Bahia,
dentro da janela), vai para `diaria/` e fica 30 dias; as outras vão para `horaria/` e ficam 3 dias.
Quem apaga é a regra de ciclo de vida do bucket; a cópia não apaga nada.

**O envio só grava nome novo.** Cada cópia é feita de exatamente duas gravações, com
`If-None-Match: *`, sem ler, listar, apagar nem mandar regra de acesso, e a credencial da cópia só
permite isso. Gravar por cima de uma cópia existente é recusado pelo próprio S3, então uma
credencial vazada não destrói as cópias.

Preparação, uma vez:

1. **Chave.** Na máquina do mantenedor, `age-keygen -o caixa-simples-copia.txt`, ou pela própria
   imagem da cópia, numa pasta fora do repositório:

   ```bash
   docker build --tag caixa-simples-copia:ensaio --file operacao/copia/Dockerfile operacao
   docker run --rm -v "<pasta fora do repositório>:/chave" caixa-simples-copia:ensaio \
     age-keygen -o /chave/caixa-simples-copia.txt
   ```

   A linha `# public key: age1...` do arquivo vai para `CAIXA_SIMPLES_COPIA_CHAVE_PUBLICA`. O arquivo
   inteiro é a chave privada: fica num gerenciador de senhas e numa cópia offline, fora da VPS e do
   Git. **Perdida a chave privada, nenhuma cópia pode ser lida, e não há como recuperá-la.**
2. **Bucket.** Um bucket só para as cópias, na região `sa-east-1`, com o bloqueio de acesso público
   e a criptografia padrão do S3, e as regras de ciclo de vida por prefixo:

   ```json
   {
     "Rules": [
       { "ID": "horaria-3-dias", "Status": "Enabled", "Filter": { "Prefix": "horaria/" }, "Expiration": { "Days": 3 } },
       { "ID": "diaria-30-dias", "Status": "Enabled", "Filter": { "Prefix": "diaria/" }, "Expiration": { "Days": 30 } }
     ]
   }
   ```

   Aplicadas com `aws s3api put-bucket-lifecycle-configuration --bucket <bucket>
   --lifecycle-configuration file://ciclo-de-vida.json`. O S3 conta os dias até a meia-noite UTC
   seguinte, então cada cópia fica até um dia além do prazo.
3. **Usuário da cópia.** Um usuário do IAM com uma chave de acesso, que vai em
   `CAIXA_SIMPLES_COPIA_AWS_*`, e só esta política, que permite gravar e mais nada. A segunda
   permissão deixa iniciar e enviar partes de um upload grande; a conclusão ainda exige
   `If-None-Match`:

   ```json
   {
     "Version": "2012-10-17",
     "Statement": [
       {
         "Sid": "GravaCopiaNovaSemSobrescrever",
         "Effect": "Allow",
         "Action": "s3:PutObject",
         "Resource": "arn:aws:s3:::<bucket>/*",
         "Condition": { "Null": { "s3:if-none-match": "false" } }
       },
       {
         "Sid": "EnviaPartesSemCriarObjeto",
         "Effect": "Allow",
         "Action": "s3:PutObject",
         "Resource": "arn:aws:s3:::<bucket>/*",
         "Condition": { "Bool": { "s3:ObjectCreationOperation": "false" } }
       }
     ]
   }
   ```

4. **Leitura para restaurar.** Outra credencial, só do mantenedor e fora da VPS, com leitura e
   listagem do bucket das cópias e do de remoções:

   ```json
   {
     "Version": "2012-10-17",
     "Statement": [
       { "Effect": "Allow", "Action": "s3:ListBucket", "Resource": ["arn:aws:s3:::<bucket>", "arn:aws:s3:::<bucket de remoções>"] },
       { "Effect": "Allow", "Action": "s3:GetObject", "Resource": ["arn:aws:s3:::<bucket>/*", "arn:aws:s3:::<bucket de remoções>/*"] }
     ]
   }
   ```

**Conferir depois de publicar.** A primeira cópia chega ao bucket na hora cheia seguinte, em
`horaria/`, e a das 6h UTC em `diaria/`. Com a credencial da cópia, gravar de novo o nome de uma cópia
existente tem de ser recusado: com o cabeçalho, o S3 responde que a condição falhou; sem ele, nega o
acesso. À mão, `systemctl start caixa-simples-copia.service` roda uma cópia na hora, e
`journalctl -u caixa-simples-copia --since -1h` mostra o resultado.

## Restauração

A cópia no S3 é o único backup: a VPS não tem backup do fornecedor em uso. Restauração nunca ensaiada
não prova que a cópia serve, então ela é ensaiada antes da primeira Conta real, pelos dois caminhos
abaixo, e depois **uma vez por mês**, pelo da VPS, anotando data, cópia usada, contagens, totais e
resultado. O registro externo das remoções fica em outro bucket, por 45 dias, mais que a cópia mais
antiga.

Com dado real, a cópia só é restaurada na VPS. O caminho local decifra o banco inteiro num computador
pessoal, fora do alcance das remoções feitas depois, e o volume apagado do Docker Desktop fica num disco
virtual que não encolhe, onde os dados podem seguir legíveis. Ele serve às cópias do
[ensaio](#ensaio-local) e às de Contas de teste.

[`copia/restaurar.sh`](copia/restaurar.sh) busca o par, confere que o banco de destino está vazio,
decifra direto para o `pg_restore`, sem dump em claro no disco, e roda a conferência no banco
restaurado contra a que foi gravada na origem. Antes de liberar acesso, reaplica os registros de
remoção do bucket separado. Uma cópia anterior à coluna `cliente.removido_em` precisa receber as
migrations em ambiente isolado antes dessa reaplicação. Termina com `confere` e código 0, ou com a
diferença e código 1. A credencial de leitura e a chave privada entram só no comando, pelo ambiente e
por `-v`, e nunca ficam na máquina. Para ver as cópias, `rclone lsf s3:<bucket>/diaria/` com a
mesma credencial.

**Num PostgreSQL 17 local**, só sem dado real, o vazio do ensaio, na máquina do mantenedor, com o
[ensaio](#ensaio-local) preparado:

```bash
export CAIXA_SIMPLES_LEITURA_AWS_ACCESS_KEY_ID=<chave de leitura>
read -rs CAIXA_SIMPLES_LEITURA_AWS_SECRET_ACCESS_KEY && export CAIXA_SIMPLES_LEITURA_AWS_SECRET_ACCESS_KEY
CAIXA_SIMPLES_REMOCOES_DESTINO=s3:<bucket de remoções>/remocoes sh operacao/vps/compose.sh \
  --profile restauracao run --rm -T \
  -e CAIXA_SIMPLES_RESTAURAR_BANCO=postgresql://restaurado@restaurado:5432/restaurado \
  -v "<arquivo da chave privada>:/chave/chave-privada.txt:ro" \
  restaurar s3:<bucket>/diaria/caixa-simples-<instante UTC>
```

**Num banco vazio na própria VPS**, num projeto de compose à parte, na janela, porque ocupa mais
512 MB enquanto roda. O banco desse projeto nasce com o papel `caixa_simples` pelo `initdb`, e a
restauração conecta com ele, sem superusuário, como faria na recriação da máquina. Como root, na pasta
`operacao/vps`, com a credencial de leitura exportada como acima e a chave privada copiada só para este
comando:

```bash
sh compose.sh -p caixa-simples-restauracao up -d --wait banco
sh compose.sh -p caixa-simples-restauracao --profile restauracao run --rm -T --no-deps \
  -v "<arquivo da chave privada>:/chave/chave-privada.txt:ro" \
  restaurar s3:<bucket>/diaria/caixa-simples-<instante UTC>
sh compose.sh -p caixa-simples-restauracao --profile restauracao down -v
```

Depois, apague a chave privada da máquina.

## Recriação da VPS

Para uma máquina nova, quando a atual se perde ou é trocada. O banco volta da cópia mais recente, e a
perda fica no que foi escrito depois dela, no máximo uma hora.

1. Contratar a máquina e repetir os passos 3 a 5 da [primeira publicação](#primeira-publicação), com
   a mesma chave do CI.
2. Preencher a configuração. O segredo dos planos e as mensalidades vêm da cópia guardada pelo
   mantenedor; a senha do banco e a chave dos tokens podem ser as novas geradas, e os usuários
   entram de novo.
3. Restaurar no banco desta máquina, ainda vazio, antes de qualquer deploy, que criaria o schema
   num banco novo. Como root, na pasta `operacao/vps`, com a credencial de leitura exportada como em
   [Restauração](#restauração) e a versão que vai ao ar:

   ```bash
   export CAIXA_SIMPLES_VERSAO=<SHA do último commit aprovado>
   sh compose.sh --profile restauracao run --rm -T \
     -v "<arquivo da chave privada>:/chave/chave-privada.txt:ro" \
     restaurar s3:<bucket>/horaria/caixa-simples-<instante UTC da mais recente>
   /usr/local/sbin/caixa-simples-implantar "$CAIXA_SIMPLES_VERSAO"
   ```

   O banco sobe pela primeira vez com a restauração e cria, no `initdb`, o papel `caixa_simples` com
   a senha da configuração nova; a cópia entra nele como esse papel, sem superusuário. O deploy sobe a
   aplicação sobre o banco restaurado e aplica as migrations que a cópia ainda não tem.
4. Apontar o registro A do domínio para o IP novo; o proxy obtém o certificado assim que o domínio
   chegar à máquina.
5. No GitHub, atualizar `VPS_ENDERECO` e `VPS_CHAVE_DO_HOST`.
6. `systemctl start caixa-simples-copia.timer caixa-simples-vigia.timer caixa-simples-recusas.timer`,
   e apagar a chave privada da máquina.

No ensaio local, a recriação inteira, num projeto de compose novo com volume vazio, terminou com a
conferência em `confere`, a aplicação de pé em 43 segundos e o login de uma Conta que veio da cópia.

## Remoção de dados e encerramento da Conta

O ADMIN remove Cliente no aplicativo. Se houver dívida ou comanda ABERTA, a operação responde 409;
após quitar ou cancelar a Venda elegível, o pedido pode ser repetido. A inativação de Usuário apaga
a credencial, e o ADMIN pode anonimizar o nome depois. Essas ações gravam antes um registro mínimo
em objetos `remocoes/<conta>/<tipo>/<id>/<instante>.json` num bucket S3 separado do backup, na
região `sa-east-1`. A aplicação recebe `CAIXA_SIMPLES_REMOCOES_BUCKET` e uma credencial limitada a
gravar nesse bucket. Sem acesso ao registro, a API responde 503 e não remove o dado do banco.

O servidor não alcança o aparelho. O que um aparelho guardou, inclusive o cadastro de Clientes, só sai
dele por Apagar os dados deste aparelho, na tela de entrada, ou pela limpeza dos dados do site no
navegador; a tela Usuários lembra disso ao inativar alguém. A edição de um Cliente esvazia, na mesma
transação, o conteúdo dos gestos anteriores dele no registro de sincronização, como a remoção.

O registro serve para a restauração reaplicar as remoções feitas depois da cópia, e a cópia mais
antiga vive até 31 dias. Por isso cada registro fica 45 dias, com folga para uma restauração ou uma
recriação começada no fim da vida da cópia, e depois o próprio bucket o apaga. Enquanto a Conta existe,
a remoção continua visível no banco: o Cliente fica marcado como removido, e o Usuário, inativo, sem
credencial e, se pedido, sem o nome. Ao criar o bucket, com o bloqueio de acesso público e a
criptografia padrão, como o das cópias, aplique a regra:

```json
{
  "Rules": [
    { "ID": "remocoes-45-dias", "Status": "Enabled", "Filter": { "Prefix": "remocoes/" }, "Expiration": { "Days": 45 } }
  ]
}
```

com `aws s3api put-bucket-lifecycle-configuration --bucket <bucket de remoções>
--lifecycle-configuration file://remocoes-45-dias.json`, e confira com
`aws s3api get-bucket-lifecycle-configuration --bucket <bucket de remoções>`. A aplicação e o
encerramento gravam sob o prefixo `remocoes/`; a restauração reaplica todo registro que encontra, então
o prazo não muda nada nela.

**Pedido de Cliente ou de funcionário que chega ao Caixa Simples.** Quem decide sobre esses dados é a
Conta, e o Caixa Simples só age sobre eles por instrução dela: o pedido é encaminhado, nunca atendido
direto. Em até 2 dias úteis:

1. Ache a Conta pelo nome do negócio que a pessoa deu, lido pelo `read`, com os e-mails dos
   administradores ativos dela, como root, na pasta `operacao/vps`:

   ```bash
   read -rp 'Nome do negócio: ' NEGOCIO
   sh compose.sh exec -T banco psql -U postgres -d caixa_simples -v negocio="$NEGOCIO" <<'FIM'
   select c.id, c.nome_negocio, cr.email
   from conta c join usuario u on u.conta_id = c.id and u.perfil = 'ADMIN' and u.ativo
   join credencial cr on cr.usuario_id = u.id
   where c.nome_negocio ilike '%' || :'negocio' || '%';
   FIM
   unset NEGOCIO
   ```

2. Encaminhe a cada administrador o pedido como chegou, com o contato que a pessoa deu, e nada mais.
   O Caixa Simples não consulta nem muda os dados dela para responder.
3. Responda à pessoa que o Caixa Simples trata esses dados em nome do negócio, que o pedido foi
   encaminhado a ele e que a resposta vem dele. Sem o nome do negócio, pergunte qual é, sem dizer se há
   dado dela em alguma Conta.

O pedido, o encaminhamento e a resposta ficam no e-mail de atendimento cinco anos depois da resposta,
como prova de que foram atendidos no prazo, e depois são apagados. O pedido do administrador sobre o
cadastro da Conta ou o dele não é encaminhado: quem decide sobre esse cadastro é o Caixa Simples.

O ADMIN pede encerramento pelo endereço publicado no aviso de privacidade. Antes de executar, confira
a identidade e o vínculo do ADMIN com a Conta e anote a data do pedido para cumprir o prazo de quinze
dias. Responda ao ADMIN que, em cada aparelho da Conta, cada usuário entre com rede, espere o envio do
que registrou sem rede e saia, e que então alguém toque em Apagar os dados deste aparelho, na tela de
entrada: o aparelho guarda o cadastro, com nome e contato dos Clientes, e o Caixa Simples não o
alcança. O botão continua na tela de entrada depois do encerramento, mas o que não tiver sido enviado
até lá se perde. Na hora de executar, pare a aplicação para impedir novas escritas, com
`sh compose.sh stop app`. Retire de
`/etc/caixa-simples/efi.env` todas as linhas da Conta, que começam por
`CAIXA_SIMPLES_EFI_<UUID_DA_CONTA_SEM_HIFENS>_`, e apague o certificado dela em
`/etc/caixa-simples/efi/`. Confira que nenhuma linha com esse prefixo restou e guarde a evidência
dessa conferência fora do banco a apagar. O pedido, a data e a evidência ficam guardados no Brasil por
cinco anos depois do encerramento, como prova de que ele foi pedido e feito no prazo, e depois são
apagados; o registro do encerramento no bucket de remoções segue os 45 dias dos outros.

Depois, como root, na pasta `operacao/vps`:

```bash
CAIXA_SIMPLES_SEGREDOS_REMOVIDOS=sim sh compose.sh --profile exclusao run --rm -T \
  encerrar <uuid da Conta> <uuid do ADMIN>
sh compose.sh up -d --wait app
```

A confirmação `CAIXA_SIMPLES_SEGREDOS_REMOVIDOS=sim` vai só no comando, depois da conferência. O
script verifica se o solicitante é ADMIN ativo da Conta, grava o registro externo com a credencial
de gravação do bucket de remoções, apaga em uma transação as linhas da Conta e confere que ela não
existe mais. A Conta de outro tenant continua. Uma falha depois do registro externo exige investigar
antes de reabrir o serviço; uma restauração reaplica o encerramento. O ensaio local usa o mesmo
serviço e o volume `remocoes`, separado de `copias`. A restauração precisa de leitura nos dois
destinos.

Por fim, o histórico do shell. Os comandos deste documento leem o e-mail e o nome do negócio pelo
`read`, mas um deles pode ter sido digitado à mão. Como root, com os dois lidos da mesma forma:

```bash
read -rp 'E-mail do administrador: ' EMAIL
read -rp 'Nome do negócio: ' NEGOCIO
for historico in /root/.bash_history /home/*/.bash_history; do
  [ -f "$historico" ] || continue
  if grep -qiF -e "$EMAIL" -e "$NEGOCIO" "$historico"; then
    grep -viF -e "$EMAIL" -e "$NEGOCIO" "$historico" > /root/historico.tmp
    cat /root/historico.tmp > "$historico" && rm /root/historico.tmp
    echo "linhas apagadas de $historico"
  fi
done
unset EMAIL NEGOCIO
```

A busca é por texto, sem expressão regular, e sem diferença entre maiúscula e minúscula; um nome de
negócio curto pode levar junto linhas que não são dele, o que no histórico não faz falta.

## Vigia

Três checks no Healthchecks.io, plano gratuito, com aviso por e-mail ao mantenedor quando um sinal
esperado não chega ou quando chega o de falha:

| Check | Período | Tolerância | Quem manda o sinal |
|---|---|---|---|
| cópia | 1 hora | 30 minutos | [`copia-agendada.sh`](vps/copia-agendada.sh), depois de cada cópia: o sinal, ou o de falha |
| máquina | 5 minutos | 10 minutos | [`vigia.sh`](vps/vigia.sh), depois de conferir a aplicação pelo domínio, passando pelo proxy e pelo certificado |
| recusas | 5 minutos | 10 minutos | [`vigia-de-recusas.sh`](vps/vigia-de-recusas.sh), depois de contar as recusas dos últimos 5 minutos no log da aplicação: o sinal, ou o de falha acima de 50 |

O endereço de sinal de cada check vai na [configuração](#configuração). O sinal é um GET sem corpo, só
com o endereço do check: nenhum log e nada das Contas sai da máquina por ele. Cobrem a hora sem cópia,
a máquina ou a rede fora do ar, a aplicação parada ou travada, o certificado vencido e a rajada de
recusas. O deploy que falha aparece no CI, que fica vermelho, e o GitHub avisa por e-mail.

**O alerta de recusas** conta os [eventos de segurança](#a-vps) de login recusado, troca de senha
recusada, token recusado, acesso recusado por perfil e aviso Pix recusado. Uma origem sozinha não passa
de 30 falhas de login na janela da contenção; acima de 50 em 5 minutos é força bruta distribuída, ou
enxurrada de token falso ou de aviso Pix, que nada limita. Com o aviso, a origem e a Conta atacada estão
no log, e uma origem pode ser bloqueada como em [Incidente de segurança](#incidente-de-segurança):

```bash
journalctl CONTAINER_NAME=caixa-simples-app-1 --since -30min | grep -E ' evento=[a-z_.]*recusad' \
  | grep -oE 'origem=[^ ]+' | sort | uniq -c | sort -rn | head
```

Sem conseguir ler o log, o script não manda sinal nenhum, e o check só cai depois da tolerância: a troca
da aplicação no deploy não vira alerta. Numa configuração gerada antes do alerta, a linha
`CAIXA_SIMPLES_VIGIA_RECUSAS=` entra à mão, porque o gerador nunca sobrescreve o arquivo.

## Ensaio local

A produção em miniatura, na máquina do mantenedor, pelo mesmo [`compose.yaml`](vps/compose.yaml) da
VPS, com [`ensaio/compose.yaml`](ensaio/compose.yaml) por cima, e pelos mesmos scripts. O que muda: as
imagens são construídas deste checkout; a CPU é dividida com soma de uma (aplicação 0,6, banco 0,3,
proxy 0,1), o pior caso de a vCPU da VPS ser disputada; o proxy atende em 18080 e 18443, com o domínio
`localhost` e uma autoridade local dele; as cópias e as remoções vão para volumes do Docker no lugar do
S3; e há um PostgreSQL vazio para a restauração. Comandos a partir da raiz do repositório; no Git Bash
do Windows, os caminhos no formato `C:/...`:

```bash
# Configuração e estado do ensaio, em pastas fora do repositório.
export CAIXA_SIMPLES_CONFIGURACAO=<pasta>/configuracao CAIXA_SIMPLES_ESTADO=<pasta>/estado
export CAIXA_SIMPLES_COMPOSE_ADICIONAL="$(pwd -W 2>/dev/null || pwd)/operacao/ensaio/compose.yaml"
sh operacao/vps/criar-configuracao.sh "$CAIXA_SIMPLES_CONFIGURACAO"
mkdir -p "$CAIXA_SIMPLES_ESTADO" && echo CAIXA_SIMPLES_VERSAO=ensaio > "$CAIXA_SIMPLES_ESTADO/versao.env"
# No arquivo gerado: CAIXA_SIMPLES_DOMINIO=localhost, as mensalidades (10.00 e 20.00),
# CAIXA_SIMPLES_COPIA_DESTINO=/copias/horaria, CAIXA_SIMPLES_COPIA_DESTINO_DIARIA=/copias/diaria,
# CAIXA_SIMPLES_REMOCOES_DESTINO=/remocoes e, para a cópia agendada, endereços de um vigia local.

# Proxy, aplicação e banco, em https://localhost:18443, depois das migrations e do healthcheck; e a
# raiz da autoridade local do proxy, para o Node e o curl confiarem nela.
sh operacao/vps/compose.sh up -d --build --wait proxy app banco
sh operacao/vps/compose.sh cp proxy:/data/caddy/pki/authorities/local/root.crt <pasta>/proxy-raiz.crt

# Uma Conta por execução; para a carga, três, com a mesma senha. Cada uma pede o plano completo na
# tela Plano, e o código sai do script com o segredo da configuração do ensaio.
CAIXA_SIMPLES_SEED_NOME_NEGOCIO="Loja da Esquina" CAIXA_SIMPLES_SEED_EMAIL=<e-mail> \
CAIXA_SIMPLES_SEED_SENHA=<senha> sh operacao/vps/compose.sh --profile seed run --rm seed

# Carga, e a memória dos contêineres depois dela.
NODE_EXTRA_CA_CERTS=<pasta>/proxy-raiz.crt CARGA_URL=https://localhost:18443 \
  CARGA_EMAILS=<e-mail 1>,<e-mail 2>,<e-mail 3> CARGA_SENHA=<senha> node operacao/carga/carga.mjs
for c in app banco proxy; do docker exec caixa-simples-ensaio-$c-1 cat /sys/fs/cgroup/memory.peak; done
docker inspect -f '{{.RestartCount}} reinícios, OOMKilled={{.State.OOMKilled}}' caixa-simples-ensaio-app-1

# A troca da aplicação, como no deploy, para ver a espera do proxy.
sh operacao/vps/compose.sh up -d --force-recreate --no-deps --wait app

# Cópia para a pasta horaria, com um par de chaves só do ensaio, gerado como na preparação e com a
# chave pública na configuração; com a hora diária igual à hora UTC de agora, vai para a diaria.
sh operacao/vps/compose.sh --profile copia build copia
sh operacao/vps/copia-agendada.sh
CAIXA_SIMPLES_COPIA_HORA_DIARIA=<hora UTC de agora, sem zero à esquerda> sh operacao/vps/copia-agendada.sh

# Restauração de uma cópia no PostgreSQL vazio.
sh operacao/vps/compose.sh --profile restauracao run --rm -T \
  -e CAIXA_SIMPLES_RESTAURAR_BANCO=postgresql://restaurado@restaurado:5432/restaurado \
  -v "<arquivo da chave privada>:/chave/chave-privada.txt:ro" restaurar /copias/diaria/caixa-simples-<instante UTC>

# Fim: contêineres e volumes do ensaio.
sh operacao/vps/compose.sh --profile seed --profile redefinir-senha --profile copia --profile restauracao --profile exclusao down -v
```

No Git Bash do Windows, o `curl` do sistema confere a revogação do certificado, que a autoridade local
do proxy não publica: use `--ssl-no-revoke` junto de `--cacert`.

**O limite de conexões no ensaio.** Ele é da máquina, não do compose: no ensaio, carrega-se a mesma tabela
na máquina virtual do Docker Desktop, onde ela some quando o Docker reinicia. O encaminhador de portas do
Windows entrega a conexão lá dentro por um processo local, que não passa pela cadeia de encaminhamento;
quem passa por ela, como uma origem de fora, é um cliente noutra rede do Docker que chega à porta
publicada pelo gateway dessa rede, com o nome `localhost` no TLS. O Docker isola as redes entre si, e no
ensaio uma regra de aceite na cadeia `DOCKER-USER` libera a do cliente. Cada contêiner nela é uma origem.
Dentro do proxy, todas aparecem com o gateway da rede do ensaio, porque o Docker mascara a saída; o
limite conta antes disso, pelo endereço do cliente.

```bash
# A tabela, com o nftables do AlmaLinux 9, a mesma versão da VPS; para tirar, nft delete table no lugar do -f.
docker run --rm --privileged --net=host -v "$(pwd -W)/operacao/vps/limite-de-conexoes.nft:/limite.nft:ro" \
  almalinux:9 sh -c 'dnf -y -q install nftables && nft -f /limite.nft'

# Uma rede para os clientes, liberada até a porta publicada, e o gateway que eles usam como destino.
docker network create ensaio-clientes
ponte=br-$(docker network inspect ensaio-clientes -f '{{.Id}}' | cut -c1-12)
docker run --rm --privileged --pid=host alpine:3 nsenter -t 1 -m -n -- iptables -I DOCKER-USER -i "$ponte" -j ACCEPT
docker network inspect ensaio-clientes -f '{{(index .IPAM.Config 0).Gateway}}'
```

A carga atrás de um endereço só roda num contêiner dessa rede, com o Node do estágio de build da imagem
(`docker build --target build`, em `/build/frontend/node/node`), um `/etc/hosts` montado que aponta
`localhost` para o gateway, `CARGA_URL=https://localhost:18443` e a raiz do proxy em
`NODE_EXTRA_CA_CERTS`.

## Carga de correção

[`carga/carga.mjs`](carga/carga.mjs), com Node 20 ou mais novo e sem dependência. Em três Contas, em
paralelo: abre o caixa, faz 100 Vendas com rede (dinheiro com troco, cartão e fiado, em rodízio), uma
sangria e um lote de 100 gestos registrados sem rede, 25 Vendas de 4 gestos, enviado duas vezes.
Depois confere a lista de Vendas do caixa e o relatório do dia contra o que fez, e fecha o caixa com
o valor esperado que calculou.

Passa com nenhuma resposta 5xx, nenhuma Venda perdida nem duplicada, inclusive no reenvio do lote,
e a lista, o relatório e o fechamento batendo com os totais da carga; o reinício da instância durante
a carga se confere à parte, como acima. A latência sai como linha de base, p50 e p95 por rota, sem
meta.

Roda contra a instalação publicada antes da primeira Conta real, em Contas de teste criadas pelo
seed, e de novo depois de qualquer troca de plano da VPS ou de parâmetro da JVM. Cada e-mail é o
administrador de uma Conta, todos com a mesma senha, e o caixa dele precisa estar fechado. A Conta
precisa do plano completo ativado pelo [pedido de plano](#pedido-de-plano-e-código-de-ativação),
porque a carga confere o relatório do dia e usa o estoque quando ele está ligado; sem o recurso, ela
para antes de começar.
