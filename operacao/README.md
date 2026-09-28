# Operação

Tudo o que põe o sistema no ar e o mantém de pé: a imagem, o CI que dispara a publicação, o
template da hospedagem, a cópia cifrada de hora em hora, a restauração, a carga de correção e o
ensaio local de tudo isso.

Nenhum dado pessoal sai do Brasil, nem na cópia cifrada. A hospedagem é o Northflank, num projeto
na região dele no Brasil (`southamerica-east`), e a cópia vai para um bucket do S3 da AWS na região
dela no Brasil (`sa-east-1`), outro fornecedor. Só a construção das imagens roda fora do projeto,
na infraestrutura de build do Northflank, e ela vê o código deste repositório, nenhum dado.

| Arquivo | O que é |
|---|---|
| [`Dockerfile`](../Dockerfile) | Imagem do jar único, com a API e o aplicativo na mesma origem |
| [`.github/workflows/ci.yml`](../.github/workflows/ci.yml) | Testes Java e do front-end, build e lint a cada push no `main` e a cada pull request; no push verde, o gatilho da publicação |
| [`northflank.json`](../northflank.json) | Template: projeto, banco, builds, serviço, jobs, grupos de segredo e o fluxo de publicação |
| [`copia/`](copia/) | Imagem do job, a cópia, a restauração e a conferência entre as duas |
| [`carga/carga.mjs`](carga/carga.mjs) | Carga de correção contra uma instalação |
| [`ensaio/compose.yaml`](ensaio/compose.yaml) | A produção em miniatura, na máquina local |

## Do push à produção

1. O push no `main` dispara o CI: `sh mvnw -B -ntp verify`, que roda os testes Java contra um
   PostgreSQL em contêiner, os testes e o build do front-end e gera o jar, e depois o lint do
   front-end.
2. Com os dois verdes, e com a variável do repositório `PUBLICAR_NO_NORTHFLANK` valendo `true`, o
   job `publicar` chama o gatilho do fluxo `publicacao` com o commit. Sem a variável, o CI testa e
   não publica. O gatilho é um endereço secreto que só dispara esse fluxo: quem o tiver consegue,
   no máximo, publicar outro commit deste repositório.
3. O fluxo constrói as duas imagens daquele commit, a da aplicação e a do job da cópia, e publica
   a primeira no serviço e no job do seed e a segunda no job da cópia.
4. O contêiner novo aplica as migrations, valida o schema contra as entidades e só recebe tráfego
   depois de passar nas sondas de partida e de prontidão; até lá, o antigo continua servindo.

**Migration só na janela de manutenção.** Todo push verde no `main` publica, então o commit com
migration nova só é enviado entre 0h e 5h no fuso America/Bahia (3h às 8h UTC): enquanto a
instância nova aplica o schema, a antiga continua servindo com o de antes. Deploy sem migration pode
ir a qualquer hora. A mesma janela vale para troca de plano do banco, restauração e qualquer
manutenção que possa derrubar o serviço.

## Imagem

Três estágios: o build, com o Maven e o Node que o `pom.xml` fixa, em imagem com glibc; a extração
do jar em camadas, das que mudam menos para as que mudam mais; e a execução, só com o JRE e sem root.
Os testes do front-end rodam no build da imagem, porque fazem parte do empacotamento. Os testes Java
não, porque sobem um PostgreSQL em contêiner e o build não tem Docker; quem os garante é o CI, antes
da publicação.

A JVM foi medida sob a carga de correção com meia CPU, no limite de 512 MB e no de 1 GB do plano da
hospedagem: `-XX:+UseSerialGC -XX:MaxRAMPercentage=45 -XX:+ExitOnOutOfMemoryError`, pelo
`JAVA_TOOL_OPTIONS` da imagem. O porquê de cada valor está no próprio `Dockerfile`. Um
`JAVA_TOOL_OPTIONS` definido no serviço substitui o da imagem inteiro: repita os três valores e meça
de novo com a carga.

## O template da hospedagem

O [`northflank.json`](../northflank.json) descreve o ambiente inteiro, sem segredo:

| Recurso | O que é | Para quê |
|---|---|---|
| projeto `caixa-simples` | projeto na região do Brasil | contém todo o resto; a região não muda depois de criado |
| environment `producao` | ambiente | onde mora o fluxo de publicação |
| addon `banco` | PostgreSQL 17, `nf-compute-20`, 4 GB, TLS ligado, sem acesso externo, sem backup agendado | o banco; o backup é a cópia de hora em hora, porque o fornecedor não documenta onde guarda o dele |
| `build-aplicacao` e `build-copia` | build services, CI desligado | constroem as duas imagens, só quando o fluxo pede |
| serviço `caixa-simples` | deployment service, `nf-compute-50`, uma instância, porta 8080 pública | a aplicação |
| job `seed` | job avulso, `nf-compute-50` | cria uma Conta |
| job `copia` | job agendado, `nf-compute-10`, `0 * * * *`, sem duas execuções ao mesmo tempo | a cópia cifrada |
| `segredos-aplicacao` | grupo de segredos, só do serviço e do seed | banco e chave dos tokens |
| `segredos-copia` | grupo de segredos, só do job da cópia | banco, chave pública e credencial do bucket |
| `segredos-pix-efi` | grupo de segredos, só do serviço | credenciais Pix de cada Conta, preenchidas no painel |
| fluxo `publicacao` | workflow do environment, com gatilho webhook | constrói o commit do argumento `sha` e o publica |

Os valores de cada instalação entram como argument overrides do template, guardados no Northflank
e nunca no arquivo:

| Argumento | Valor |
|---|---|
| `COPIA_BUCKET` | Nome do bucket das cópias |
| `COPIA_CHAVE_PUBLICA` | Chave pública do age, a linha que começa com `age1` |
| `COPIA_AWS_ACCESS_KEY_ID` e `COPIA_AWS_SECRET_ACCESS_KEY` | Chave de acesso do usuário do job no IAM |

O arquivo traz só `apiVersion`, `arguments` e `spec`, o formato que o Northflank lê de um
repositório; nome, execução automática e concorrência do template se configuram na criação dele.
Ele é colado no editor de código do template, sem GitOps: com GitOps, a sincronização vai nos dois
sentidos, e uma edição no painel viraria commit neste repositório.

**Rodar o template de novo aplica a configuração e não publica código.** O serviço e os jobs têm a
origem da imagem controlada pelo fluxo de publicação, e a build de um commit já construído é
reaproveitada. Um ajuste feito no painel num campo que o template declara é desfeito na execução
seguinte: a mudança vai no arquivo. O grupo `segredos-pix-efi` é a exceção: o template só o cria e
nunca mais mexe nele.

## Variáveis do serviço

| Variável | De onde vem | Para quê |
|---|---|---|
| `CAIXA_SIMPLES_DB_URL` | `JDBC_POSTGRES_URI` do addon, apelidada no grupo `segredos-aplicacao` | URL JDBC interna, já com o TLS do addon |
| `CAIXA_SIMPLES_DB_USER` e `CAIXA_SIMPLES_DB_PASSWORD` | `USERNAME` e `PASSWORD` do addon, pelo mesmo grupo | Credencial do usuário comum do banco, não a do administrador |
| `CAIXA_SIMPLES_JWT_SECRET` | Gerada pelo Northflank na primeira execução do template e guardada no grupo | Assinatura dos tokens, com ao menos 32 bytes. Trocá-la obriga todo mundo a entrar de novo |
| `CAIXA_SIMPLES_PORT` | Template | 8080, a porta pública do serviço |
| `CAIXA_SIMPLES_PROXIES_CONFIAVEIS` | Opcional | Declara um proxy público na frente da aplicação; ver [Borda](#borda) |
| `CAIXA_SIMPLES_EFI_<UUID_DA_CONTA_SEM_HIFENS>_*` | À mão, no grupo `segredos-pix-efi`, por Conta que recebe Pix | `AMBIENTE`, `CLIENT_ID`, `CLIENT_SECRET`, `CHAVE_PIX`, `CERTIFICADO_P12`, `CERTIFICADO_SENHA`, `WEBHOOK_ID` e `WEBHOOK_SECRET` |

O certificado `.p12` de cada Conta entra como arquivo secreto do grupo `segredos-pix-efi`, montado
num caminho escolhido na hora, como `/segredos/efi/<uuid da Conta>.p12`; é esse caminho que vai em
`CERTIFICADO_P12`.

## Primeira publicação

1. **Antes de criar qualquer recurso**, confirmar os preços do dia, que a conta cria projeto na
   região do Brasil com o PostgreSQL 17, e, pelo suporte do Northflank, que nada do volume do banco
   é copiado para fora do país quando nenhum backup está agendado, e onde ficam os logs da
   plataforma.
2. Conta no Northflank com o GitHub ligado a ela, com acesso a este repositório, e um e-mail que
   receba as faturas.
3. Preparar a AWS e a chave da cópia, como em [Cópia de hora em hora](#cópia-de-hora-em-hora-cifrada).
4. Criar o template: colar o `northflank.json` no editor de código, com o nome `caixa-simples`,
   execução automática desligada e concorrência `forbid`, e preencher os argument overrides.
5. Rodar o template com o último commit do `main` verde no CI. A primeira execução cria o projeto e
   o banco, constrói o `main`, espera o banco ficar pronto e cria o serviço e os jobs com essa build.
   O serviço sobe, aplica as migrations e passa a responder no endereço `code.run` da porta, com TLS
   gerenciado.
6. No fluxo `publicacao`, abrir o gatilho webhook e copiar o endereço. No GitHub, gravar o endereço
   no segredo do repositório `NORTHFLANK_WEBHOOK_PUBLICACAO` e criar a variável
   `PUBLICAR_NO_NORTHFLANK` com `true`. A partir daí, cada push verde no `main` publica. Para trocar
   o endereço, regenerá-lo no gatilho e atualizar o segredo.
7. Ligar o [aviso de falha](#aviso-de-falha).
8. Pelo shell do serviço, conferir `cat /sys/fs/cgroup/memory.max`: 1073741824 é o limite em que a
   JVM foi medida. Um limite menor pede nova medição com a carga.
9. Conferir a [borda](#borda) pelo access log.
10. Criar as Contas de teste pelo [seed](#criar-uma-conta), rodar a [carga](#carga-de-correção)
    e ensaiar a [restauração](#restauração) pelos dois caminhos.

## Borda

A entrada do Northflank termina o HTTPS, acrescenta ao `X-Forwarded-For` o endereço de quem se
conectou a ela e fala com a aplicação por HTTP, pela rede interna do projeto.

A aplicação lê esse cabeçalho da direita para a esquerda: descarta os saltos da rede interna, e o
primeiro endereço fora dela é a origem. O que o cliente escreve à esquerda nunca é lido. O
`X-Forwarded-Proto` diz se a requisição chegou por HTTPS, e é o que faz a resposta levar o
`Strict-Transport-Security`.

Se aparecer um salto público entre a entrada e a aplicação, `CAIXA_SIMPLES_PROXIES_CONFIAVEIS` o
declara, sem novo build: CIDRs separados por vírgula, com a barra mesmo para um endereço só (`/32`),
porque sem nenhuma barra o valor é lido como expressão regular. Até lá, a origem passa a ser esse
salto: a falha é fechada, e nenhum endereço escrito pelo cliente vira origem.

**Conferir depois de publicar.** A documentação do Northflank diz só que o balanceador acrescenta o
`X-Forwarded-For`, então a cadeia precisa ser medida no serviço publicado. Ligue o access log do
Tomcat pelas variáveis abaixo, no serviço (cada mudança de variável reinicia o contêiner), faça uma
requisição de um aparelho cujo IP público você conhece e leia o arquivo pelo shell do serviço, com
`cat /tmp/access_log.*.log`. O primeiro campo tem de ser o seu IP; o segundo mostra a cadeia inteira,
como chegou. Depois apague as variáveis.

```
SERVER_TOMCAT_ACCESSLOG_ENABLED=true
SERVER_TOMCAT_ACCESSLOG_DIRECTORY=/tmp
SERVER_TOMCAT_ACCESSLOG_REQUEST_ATTRIBUTES_ENABLED=true
SERVER_TOMCAT_ACCESSLOG_PATTERN=%a %{X-Forwarded-For}i %{X-Forwarded-Proto}i %r %s
```

## Sondas

| Sonda | Rota | Quando falha |
|---|---|---|
| Partida | `/actuator/health/liveness`, a cada 10 s, por até cerca de 5 minutos | o contêiner é trocado; enquanto ela não passa, as outras não rodam |
| Prontidão | `/actuator/health/readiness`, a cada 10 s | o contêiner sai do tráfego até voltar |
| Vida | `/actuator/health/liveness`, a cada 30 s, três falhas seguidas | o contêiner é reiniciado |

Nenhuma consulta o banco. Com o banco fora do ar, a instância continua de pé e as requisições que
dependem dele falham até ele voltar; uma sonda que consultasse o banco reiniciaria em laço, ou
tiraria do ar, uma aplicação sem defeito, sem trazer o banco de volta. A instância só começa a
responder depois das migrations, então as sondas sem banco não deixam passar no deploy uma instância
que não o alcança.

`/actuator/health` consulta o banco e serve para conferência à mão. Nenhum outro endpoint do
Actuator é exposto: qualquer outro caminho sob `/actuator` recebe a página do aplicativo, como toda
rota desconhecida fora de `/api`.

## Criar uma Conta

Conta nasce pelo seed: uma execução avulsa da imagem, com o perfil `seed` e sem servidor web, que
cria a Conta com o administrador e termina. Nunca no serviço. Rodar de novo com o mesmo e-mail não
cria nada.

**No Northflank**, pelo job `seed`, que já traz o perfil, a aplicação sem servidor web e o banco. Ao
rodar o job, acrescente nas variáveis de ambiente da execução:

| Variável | Valor |
|---|---|
| `CAIXA_SIMPLES_SEED_NOME_NEGOCIO` | Nome do negócio |
| `CAIXA_SIMPLES_SEED_EMAIL` | E-mail do administrador |
| `CAIXA_SIMPLES_SEED_SENHA` | Senha inicial |

O log termina com `Conta <id> criada para <e-mail>`. Os valores da execução ficam nos detalhes dela,
visíveis para quem acessa o projeto, que de todo modo já alcança o banco. A senha passa pela mesma
política de qualquer senha, inclusive a verificação de vazamento.

**No ensaio local**, pelo serviço `seed` do compose; ver [Ensaio local](#ensaio-local).

## Cópia de hora em hora cifrada

A cada hora cheia, em UTC, o job `copia`:

1. lê a conferência: a contagem de linhas de cada tabela e os totais por dia das Vendas concluídas e
   dos movimentos de caixa, em [`copia/conferencia.sql`](copia/conferencia.sql);
2. roda o `pg_dump` direto para o `age`, cifrado para a chave pública do mantenedor. O dump em claro
   nunca chega ao disco, e o job não consegue decifrar o que guardou;
3. lê a conferência de novo. Se as duas leituras diferem, houve escrita no meio, e a cópia é
   refeita, até três vezes; depois da terceira, o job falha sem enviar nada, e o aviso de falha diz
   que aquela hora ficou sem cópia;
4. envia `caixa-simples-<instante UTC>.dump.age` e, por último,
   `caixa-simples-<instante UTC>.conferencia.age`, também cifrada. A cópia só está completa com o
   par.

A cópia que começa na hora `CAIXA_SIMPLES_COPIA_HORA_DIARIA`, 6h UTC (3h no fuso America/Bahia,
dentro da janela), vai para `diaria/` e fica 30 dias; as outras vão para `horaria/` e ficam 3 dias.
Quem apaga é a regra de ciclo de vida do bucket; o job não apaga nada.

**O envio só grava nome novo.** Cada cópia é feita de exatamente duas gravações, com
`If-None-Match: *`, sem ler, listar, apagar nem mandar regra de acesso, e a credencial do job só
permite isso. Gravar por cima de uma cópia existente é recusado pelo próprio S3, então uma
credencial vazada não destrói as cópias.

| Variável | Valor |
|---|---|
| `CAIXA_SIMPLES_COPIA_BANCO` | `POSTGRES_URI` do addon, apelidada no grupo `segredos-copia` |
| `CAIXA_SIMPLES_COPIA_CHAVE_PUBLICA` | Argumento `COPIA_CHAVE_PUBLICA` |
| `CAIXA_SIMPLES_COPIA_DESTINO` | `s3:<bucket>/horaria` |
| `CAIXA_SIMPLES_COPIA_DESTINO_DIARIA` | `s3:<bucket>/diaria` |
| `CAIXA_SIMPLES_COPIA_HORA_DIARIA` | `6` |
| `RCLONE_CONFIG_S3_TYPE`, `_PROVIDER` e `_REGION` | `s3`, `AWS` e `sa-east-1` |
| `RCLONE_CONFIG_S3_ACCESS_KEY_ID` e `_SECRET_ACCESS_KEY` | Argumentos `COPIA_AWS_*` |

Preparação, uma vez:

1. **Chave.** Na máquina do mantenedor, `age-keygen -o caixa-simples-copia.txt`, ou pela própria
   imagem do job, construída pelo compose do ensaio, numa pasta fora do repositório:

   ```bash
   docker compose -f operacao/ensaio/compose.yaml --profile copia build copia
   docker run --rm -v "<pasta fora do repositório>:/chave" caixa-simples-copia:ensaio \
     age-keygen -o /chave/caixa-simples-copia.txt
   ```

   A linha `# public key: age1...` do arquivo vai para o argumento `COPIA_CHAVE_PUBLICA`. O arquivo
   inteiro é a chave privada: fica num gerenciador de senhas e numa cópia offline, fora do Northflank
   e do Git. **Perdida a chave privada, nenhuma cópia pode ser lida, e não há como recuperá-la.**
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
3. **Usuário do job.** Um usuário do IAM com uma chave de acesso, que vai nos argumentos
   `COPIA_AWS_*`, e só esta política, que permite gravar e mais nada. A segunda permissão deixa
   iniciar e enviar partes de um upload grande; a conclusão ainda exige `If-None-Match`:

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

4. **Leitura para restaurar.** Outra credencial, só do mantenedor e fora do Northflank, com leitura
   e listagem do bucket:

   ```json
   {
     "Version": "2012-10-17",
     "Statement": [
       { "Effect": "Allow", "Action": "s3:ListBucket", "Resource": "arn:aws:s3:::<bucket>" },
       { "Effect": "Allow", "Action": "s3:GetObject", "Resource": "arn:aws:s3:::<bucket>/*" }
     ]
   }
   ```

**Conferir depois de publicar.** A primeira cópia chega ao bucket na hora cheia seguinte, em
`horaria/`, e a das 6h UTC em `diaria/`. Com a credencial do job, gravar de novo o nome de uma cópia
existente tem de ser recusado: com o cabeçalho, o S3 responde que a condição falhou; sem ele, nega o
acesso.

## Restauração

A cópia no S3 é o único backup: o banco não tem backup do fornecedor. Restauração nunca ensaiada não
prova que a cópia serve, então ela é ensaiada antes da primeira Conta real, pelos dois caminhos
abaixo, e depois **uma vez por mês**, pelo local, anotando data, cópia usada, contagens, totais e
resultado.

[`copia/restaurar.sh`](copia/restaurar.sh) busca o par, confere que o banco de destino está vazio,
decifra direto para o `pg_restore`, sem dump em claro no disco, e roda a conferência no banco
restaurado contra a que foi gravada na origem. Termina com `confere` e código 0, ou com a diferença e
código 1.

**Num PostgreSQL 17 local**, o vazio do ensaio, com a credencial de leitura no ambiente. Para ver as
cópias, `rclone lsf s3:<bucket>/diaria/` com as mesmas variáveis:

```bash
docker compose -f operacao/ensaio/compose.yaml --profile restauracao run --rm \
  -e RCLONE_CONFIG_S3_TYPE=s3 -e RCLONE_CONFIG_S3_PROVIDER=AWS -e RCLONE_CONFIG_S3_REGION=sa-east-1 \
  -e RCLONE_CONFIG_S3_ACCESS_KEY_ID -e RCLONE_CONFIG_S3_SECRET_ACCESS_KEY \
  -v "<arquivo da chave privada>:/chave/chave-privada.txt:ro" \
  restaurar s3:<bucket>/diaria/caixa-simples-<instante UTC>
```

**Num addon novo do Northflank**, criado no painel no mesmo projeto, com o PostgreSQL 17. Encaminhe
o addon para a máquina local com a CLI do Northflank (`northflank forward addon --projectId
caixa-simples --addonId <addon novo>`) e rode o mesmo comando acrescentando
`-e CAIXA_SIMPLES_RESTAURAR_BANCO=<URL de conexão do addon novo, com o host local do encaminhamento>`.
Conferido, o addon novo é apagado. Numa restauração de verdade, os grupos de segredo passam a apontar
para ele, dentro da janela.

## Aviso de falha

O Northflank não avisa por e-mail. Os avisos vão para um canal do Discord, por uma integração de
notificação da equipe no Northflank, com o webhook de um canal de um servidor privado. Eventos, só
deste projeto: falha de build, do fluxo de publicação e de execução de job, e contêiner que caiu ou
reiniciou. É o único sinal de uma hora sem cópia e de um deploy que não entrou. Os avisos não levam
dado pessoal: dizem qual recurso falhou e quando.

## Ensaio local

A produção em miniatura: a imagem da aplicação com meia CPU e 1 GB sem swap, como o plano do
serviço, contra um PostgreSQL 17 com 0,2 CPU e 512 MB, como o do banco, mais o seed, o job da cópia
com 0,1 CPU e 256 MB e um PostgreSQL vazio para a restauração. As duas pastas do volume `copias`
fazem o papel dos dois destinos. Comandos a partir da raiz do repositório:

```bash
# Chave dos tokens do ensaio: qualquer valor aleatório com ao menos 32 bytes.
export CAIXA_SIMPLES_JWT_SECRET="$(openssl rand -base64 48)"

# Aplicação em http://localhost:18080, depois das migrations e do healthcheck.
docker compose -f operacao/ensaio/compose.yaml up -d --build --wait banco app

# Uma Conta por execução; para a carga, três, com a mesma senha.
CAIXA_SIMPLES_SEED_NOME_NEGOCIO="Loja da Esquina" CAIXA_SIMPLES_SEED_EMAIL=<e-mail> \
CAIXA_SIMPLES_SEED_SENHA=<senha> docker compose -f operacao/ensaio/compose.yaml run --rm seed

# Carga, e a memória do contêiner depois dela.
CARGA_URL=http://localhost:18080 CARGA_EMAILS=<e-mail 1>,<e-mail 2>,<e-mail 3> CARGA_SENHA=<senha> \
  node operacao/carga/carga.mjs
docker exec caixa-simples-ensaio-app-1 cat /sys/fs/cgroup/memory.peak
docker inspect -f '{{.RestartCount}} reinícios, OOMKilled={{.State.OOMKilled}}' caixa-simples-ensaio-app-1

# Cópia para a pasta horaria, com um par de chaves só do ensaio, gerado como na preparação.
CAIXA_SIMPLES_COPIA_CHAVE_PUBLICA=<age1...> \
  docker compose -f operacao/ensaio/compose.yaml --profile copia run --rm --build copia

# A mesma cópia vai para a pasta diaria quando a hora diária é a hora UTC de agora.
CAIXA_SIMPLES_COPIA_CHAVE_PUBLICA=<age1...> CAIXA_SIMPLES_COPIA_HORA_DIARIA=<hora UTC de agora, sem zero à esquerda> \
  docker compose -f operacao/ensaio/compose.yaml --profile copia run --rm copia

# Restauração de uma cópia no PostgreSQL vazio.
docker compose -f operacao/ensaio/compose.yaml --profile restauracao run --rm \
  -v "<arquivo da chave privada>:/chave/chave-privada.txt:ro" restaurar /copias/diaria/caixa-simples-<instante UTC>

# Fim: contêineres e volumes do ensaio.
docker compose -f operacao/ensaio/compose.yaml --profile seed --profile copia --profile restauracao down -v
```

A senha do banco do ensaio tem um padrão só local; `ENSAIO_SENHA_DO_BANCO` a troca.

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

Roda contra o ambiente publicado antes da primeira Conta real, em Contas de teste criadas pelo seed,
e de novo depois de qualquer troca de plano ou de parâmetro da JVM. Cada e-mail é o administrador de
uma Conta, todos com a mesma senha, e o caixa dele precisa estar fechado.
