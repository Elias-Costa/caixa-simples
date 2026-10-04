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
| [`.dockerignore`](../.dockerignore) e [`.dockerignore`](.dockerignore) desta pasta | O que cada build de imagem enxerga: só o que compila o jar, e só os scripts que a imagem da cópia copia |
| [`.github/workflows/ci.yml`](../.github/workflows/ci.yml) | Testes Java e do front-end, build, lint e avisos de segurança das dependências a cada push no `main` e a cada pull request, e os avisos também uma vez por semana; no push verde, o gatilho da publicação |
| [`northflank.json`](../northflank.json) | Template: projeto, banco, builds, serviço, jobs, grupos de segredo e o fluxo de publicação |
| [`dependencias/`](dependencias/) | A verificação dos avisos de segurança das dependências e das imagens, e as exceções fundamentadas a ela |
| [`copia/`](copia/) | Imagem do job, a cópia, a restauração e a conferência entre as duas |
| [`carga/carga.mjs`](carga/carga.mjs) | Carga de correção contra uma instalação |
| [`plano/codigo.mjs`](plano/codigo.mjs) | O código de ativação de um pedido de plano, gerado depois do Pix conferido |
| [`ensaio/compose.yaml`](ensaio/compose.yaml) | A produção em miniatura, na máquina local |

## Do push à produção

1. O push no `main` dispara o CI, com dois jobs em paralelo. Um roda `sh mvnw -B -ntp verify`, que
   roda os testes Java contra um PostgreSQL em contêiner, os testes e o build do front-end e gera o
   jar, e depois o lint do front-end. O outro constrói as duas imagens do commit e confere os avisos
   de segurança das dependências delas e do front-end (ver
   [Avisos de segurança das dependências](#avisos-de-segurança-das-dependências)).
2. Com os dois jobs verdes, e com a variável do repositório `PUBLICAR_NO_NORTHFLANK` valendo `true`, o
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

O build enxerga só o que o [`.dockerignore`](../.dockerignore) da raiz deixa passar: o wrapper do
Maven, o `pom.xml`, `src/main/` e `frontend/`, sem os gerados do front-end e sem configuração local,
chave, certificado ou registro de execução que alguém deixe nessas pastas. Um arquivo desses em
`src/main/resources` entraria no jar, e em `frontend/public` seria servido pela aplicação a quem o
pedisse. Os padrões começam com `**/` porque, no `.dockerignore`, um padrão sem barra só vale na raiz.
A imagem da cópia tem contexto próprio, esta pasta, e um [`.dockerignore`](.dockerignore) próprio,
que deixa entrar só os scripts e as consultas que ela copia: o ambiente do ensaio, uma chave ou o
resultado de uma carga deixados aqui não chegam ao build.

A JVM foi medida sob a carga de correção com meia CPU, no limite de 512 MB e no de 1 GB do plano da
hospedagem: `-XX:+UseSerialGC -XX:MaxRAMPercentage=45 -XX:+ExitOnOutOfMemoryError`, pelo
`JAVA_TOOL_OPTIONS` da imagem. O porquê de cada valor está no próprio `Dockerfile`. Um
`JAVA_TOOL_OPTIONS` definido no serviço substitui o da imagem inteiro: repita os três valores e meça
de novo com a carga.

## Avisos de segurança das dependências

O CI confere os avisos de segurança publicados para o que vai ao ar: as bibliotecas Java dentro da
imagem da aplicação, os pacotes das duas imagens e o `package-lock.json` do front-end, inclusive as
dependências de build, que geram o pacote e o service worker servidos. A ferramenta é o Trivy, pela
imagem oficial presa por digest em [`dependencias/verificar.sh`](dependencias/verificar.sh), que
constrói as duas imagens do checkout e as examina. Roda a cada push no `main`, a cada pull request e
uma vez por semana, num job sem segredo nenhum. O mesmo script roda na máquina, com o Docker de pé,
inclusive no Git Bash do Windows:

```bash
sh operacao/dependencias/verificar.sh
```

As bases de avisos ficam no volume `caixa-simples-trivy`, e os builds usam `--pull`, então as
imagens base guardadas na máquina são atualizadas a cada execução.

**O que barra a publicação:** aviso alto ou crítico que já tenha versão corrigida. Antes do portão,
cada alvo tem o relatório completo no log, com as severidades menores e os avisos ainda sem correção,
que não barram. O job `publicar` só roda com a verificação verde. A execução semanal confere o
`main` sem publicar, para um aviso novo numa dependência que não mudou aparecer sem esperar o próximo
push; a falha dela é avisada pelo GitHub a quem mexeu por último na agenda do workflow, e o GitHub
desliga a agenda de um repositório público depois de 60 dias sem atividade.

**Exceção fundamentada:** um aviso que barra, mas não alcança o que é publicado, ou cuja correção
ainda não pode entrar, vai para [`dependencias/excecoes.yaml`](dependencias/excecoes.yaml) com o
pacote, o porquê e a data de revisão, no máximo 90 dias à frente. Vencida a data, o aviso volta a
barrar até alguém revisar. A justificativa é pública. O purl do pacote sai no relatório da ferramenta
com `--format json`.

**Ajustes que a verificação sustenta:** o `pom.xml` fixa versões do Tomcat e do Jackson mais novas
que as gerenciadas pelo Spring Boot, e as duas linhas saem quando ele gerenciar versões iguais ou mais
novas; a imagem da cópia apaga o `gosu` que a imagem base traz para um ponto de entrada que ela não
usa.

**O que fica de fora:** as dependências de teste do Java e os plugins do Maven, que não vão para a
imagem; os binários do PostgreSQL da imagem base da cópia, compilados nela, sem registro de pacote
para a ferramenta conferir; e a base examinada é a da tag no dia da verificação, que pode mudar até
o build da hospedagem. Trocar a versão da ferramenta é trocar o digest no script, conferido em mais de
um registro.

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
| `segredos-aplicacao` | grupo de segredos, só do serviço e do seed | banco, chave dos tokens, segredo dos códigos de plano e mensalidades |
| `segredos-copia` | grupo de segredos, só do job da cópia | banco, chave pública e credencial do bucket |
| `segredos-remocoes` | grupo de segredos, só do serviço | credencial de gravação do bucket de remoções |
| `segredos-pix-efi` | grupo de segredos, só do serviço | credenciais Pix de cada Conta, preenchidas no painel |
| fluxo `publicacao` | workflow do environment, com gatilho webhook | constrói o commit do argumento `sha` e o publica |

Os valores de cada instalação entram como argument overrides do template, guardados no Northflank
e nunca no arquivo:

| Argumento | Valor |
|---|---|
| `COPIA_BUCKET` | Nome do bucket das cópias |
| `COPIA_CHAVE_PUBLICA` | Chave pública do age, a linha que começa com `age1` |
| `COPIA_AWS_ACCESS_KEY_ID` e `COPIA_AWS_SECRET_ACCESS_KEY` | Chave de acesso do usuário do job no IAM |
| `REMOCOES_BUCKET` | Nome do bucket separado das remoções, sem o ciclo de vida das cópias |
| `REMOCOES_AWS_ACCESS_KEY_ID` e `REMOCOES_AWS_SECRET_ACCESS_KEY` | Credencial limitada à gravação dos objetos de remoção |
| `PLANO_SECRET` | Segredo dos códigos de ativação de plano, com ao menos 32 bytes; ver [Pedido de plano](#pedido-de-plano-e-código-de-ativação) |
| `MENSALIDADE_CAIXA_SIMPLES` e `MENSALIDADE_COMPLETO` | Mensalidades em reais, com ponto decimal; a do completo maior que a do intermediário |

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
| `CAIXA_SIMPLES_PLANO_SECRET` | Argumento `PLANO_SECRET`, pelo grupo `segredos-aplicacao` | Confere o código de ativação de cada pedido de plano. A aplicação não sobe sem ele |
| `CAIXA_SIMPLES_MENSALIDADE_CAIXA_SIMPLES` e `CAIXA_SIMPLES_MENSALIDADE_COMPLETO` | Argumentos das mensalidades, pelo mesmo grupo | Valor dos pedidos de plano. A aplicação não sobe sem elas |
| `CAIXA_SIMPLES_PORT` | Template | 8080, a porta pública do serviço |
| `CAIXA_SIMPLES_PROXIES_CONFIAVEIS` | Opcional | Declara um proxy público na frente da aplicação; ver [Borda](#borda) |
| `CAIXA_SIMPLES_REMOCOES_BUCKET` e `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY` | Grupo `segredos-remocoes` | Grava o registro mínimo antes de uma exclusão |
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
3. Preparar a AWS e a chave da cópia, como em [Cópia de hora em hora](#cópia-de-hora-em-hora-cifrada),
   e o bucket separado de remoções, com credencial de gravação para o serviço e leitura para o
   procedimento de restauração.
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
10. Criar as Contas de teste pelo [seed](#criar-uma-conta), ativar nelas o plano completo pelo
    [pedido de plano](#pedido-de-plano-e-código-de-ativação), rodar a
    [carga](#carga-de-correção) e ensaiar a [restauração](#restauração) pelos dois caminhos.

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

O log termina com `Conta <id> criada`. Os valores da execução ficam nos detalhes dela,
visíveis para quem acessa o projeto, que de todo modo já alcança o banco. A senha passa pela mesma
política de qualquer senha, inclusive a verificação de vazamento.

**No ensaio local**, pelo serviço `seed` do compose; ver [Ensaio local](#ensaio-local).

## Pedido de plano e código de ativação

Toda Conta nasce no plano gratuito. O administrador pede a adesão, o upgrade ou a renovação na tela
Plano do aplicativo, manda o texto do pedido pelo canal de atendimento e faz o Pix do valor. O
pedido sozinho não muda nada: o plano só vale com o código, e o código só sai depois do Pix
conferido no extrato.

1. Conferir no extrato o Pix com o valor do pedido. O upgrade cobra a diferença das mensalidades
   pelos dias que faltam até o vencimento, calculada com o crédito no dia do pedido: se o crédito
   caiu em outro dia, recalcular e acertar a diferença pelo canal antes do código.
2. Gerar o código com o id e o plano do texto do pedido:

   ```bash
   CAIXA_SIMPLES_PLANO_SECRET=<segredo> node operacao/plano/codigo.mjs <id do pedido> <plano>
   ```

   O plano vai como no texto (`Caixa Simples`, `Completo`) ou como no sistema. O script só
   calcula: não toca o banco nem a Conta, e o mesmo pedido sempre dá o mesmo código.
3. Mandar o código ao administrador, que o aplica na tela Plano.

O código vale para um pedido só. Se a Conta pediu de novo antes de aplicar, o pedido anterior foi
substituído e o código dele é recusado: gere o do pedido mais recente. Aplicar o mesmo código duas
vezes não ativa duas vezes.

O segredo é o argumento `PLANO_SECRET` do template, gerado uma vez com `openssl rand -base64 48` e
guardado também fora do Northflank, com o mantenedor, porque o script precisa dele. Trocá-lo invalida
os códigos ainda não aplicados. As mensalidades são os argumentos `MENSALIDADE_CAIXA_SIMPLES` e
`MENSALIDADE_COMPLETO`; mudar uma delas vale para os pedidos seguintes, e o pedido aberto mantém o
valor do dia em que foi feito.

O plano pago vence todo mês no dia da adesão. O administrador vê o aviso sete dias antes; depois do
vencimento, os recursos pagos seguem por sete dias e param no oitavo, até a renovação. Venda, caixa
e cadastro nunca param.

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
resultado. O registro externo das remoções fica em outro bucket, sem a expiração das cópias.

[`copia/restaurar.sh`](copia/restaurar.sh) busca o par, confere que o banco de destino está vazio,
decifra direto para o `pg_restore`, sem dump em claro no disco, e roda a conferência no banco
restaurado contra a que foi gravada na origem. Antes de liberar acesso, reaplica os registros de
remoção do bucket separado. Uma cópia anterior à coluna `cliente.removido_em` precisa receber as
migrations em ambiente isolado antes dessa reaplicação. Termina com `confere` e código 0, ou com a
diferença e código 1.

**Num PostgreSQL 17 local**, o vazio do ensaio, com a credencial de leitura no ambiente. Para ver as
cópias, `rclone lsf s3:<bucket>/diaria/` com as mesmas variáveis:

```bash
docker compose -f operacao/ensaio/compose.yaml --profile restauracao run --rm \
  -e RCLONE_CONFIG_S3_TYPE=s3 -e RCLONE_CONFIG_S3_PROVIDER=AWS -e RCLONE_CONFIG_S3_REGION=sa-east-1 \
  -e RCLONE_CONFIG_S3_ACCESS_KEY_ID -e RCLONE_CONFIG_S3_SECRET_ACCESS_KEY \
  -e CAIXA_SIMPLES_REMOCOES_DESTINO=s3:<bucket-de-remocoes>/remocoes \
  -v "<arquivo da chave privada>:/chave/chave-privada.txt:ro" \
  restaurar s3:<bucket>/diaria/caixa-simples-<instante UTC>
```

**Num addon novo do Northflank**, criado no painel no mesmo projeto, com o PostgreSQL 17. Encaminhe
o addon para a máquina local com a CLI do Northflank (`northflank forward addon --projectId
caixa-simples --addonId <addon novo>`) e rode o mesmo comando acrescentando
`-e CAIXA_SIMPLES_RESTAURAR_BANCO=<URL de conexão do addon novo, com o host local do encaminhamento>`.
Conferido, o addon novo é apagado. Numa restauração de verdade, os grupos de segredo passam a apontar
para ele, dentro da janela.

## Remoção de dados e encerramento da Conta

O ADMIN remove Cliente no aplicativo. Se houver dívida ou comanda ABERTA, a operação responde 409;
após quitar ou cancelar a Venda elegível, o pedido pode ser repetido. A inativação de Usuário apaga
a credencial, e o ADMIN pode anonimizar o nome depois. Essas ações gravam antes um registro mínimo
em objetos `remocoes/<conta>/<tipo>/<id>/<instante>.json` num bucket S3 separado do backup, na
região `sa-east-1`. O bucket de remoções não usa o ciclo de vida de três ou trinta dias das cópias.
A aplicação precisa de `CAIXA_SIMPLES_REMOCOES_BUCKET` e de uma credencial AWS limitada a gravar
nesse bucket; o grupo `segredos-remocoes` do template entrega as três variáveis apenas ao serviço.
Sem acesso ao registro, a API responde 503 e não remove o dado do banco.

O ADMIN pede encerramento pelo endereço publicado no aviso de privacidade. Antes de executar,
confira a identidade e o vínculo do ADMIN com a Conta, anote a data do pedido para cumprir o prazo
de quinze dias e pare o serviço para impedir novas escritas. No painel do Northflank, retire todas
as variáveis e o arquivo de certificado da Conta no grupo `segredos-pix-efi`: os nomes começam por
`CAIXA_SIMPLES_EFI_<UUID_DA_CONTA_SEM_HIFENS>_`. Confira no painel que nenhum nome com esse prefixo
restou. Guarde a evidência dessa conferência fora do banco a apagar.

Execute `exclusao/encerrar-conta.sh <uuid da Conta> <uuid do ADMIN>` com conexão ao banco e com
`CAIXA_SIMPLES_REMOCOES_DESTINO=s3:<bucket-de-remocoes>/remocoes`. Use uma credencial de gravação
do bucket e defina `CAIXA_SIMPLES_SEGREDOS_REMOVIDOS=sim` só depois da conferência no Northflank.
O script verifica se o solicitante é ADMIN ativo da Conta, grava o registro externo, apaga em uma
transação as linhas da Conta e confere que ela não existe mais. A Conta de outro tenant continua.
Uma falha depois do registro externo exige investigar antes de reabrir o serviço; uma restauração
reaplica o encerramento. O ensaio local usa o serviço `encerrar` do Compose e o volume `remocoes`,
separado de `copias`. A restauração precisa de leitura nos dois destinos.

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

# Segredo dos códigos de plano e mensalidades do ensaio: valores só para o teste.
export CAIXA_SIMPLES_PLANO_SECRET="$(openssl rand -base64 48)"
export CAIXA_SIMPLES_MENSALIDADE_CAIXA_SIMPLES=10.00 CAIXA_SIMPLES_MENSALIDADE_COMPLETO=20.00

# Aplicação em http://localhost:18080, depois das migrations e do healthcheck.
docker compose -f operacao/ensaio/compose.yaml up -d --build --wait banco app

# Uma Conta por execução; para a carga, três, com a mesma senha. Cada uma pede o plano completo
# na tela Plano, e o código sai do script com o segredo exportado acima.
CAIXA_SIMPLES_SEED_NOME_NEGOCIO="Loja da Esquina" CAIXA_SIMPLES_SEED_EMAIL=<e-mail> \
CAIXA_SIMPLES_SEED_SENHA=<senha> docker compose -f operacao/ensaio/compose.yaml run --rm seed
node operacao/plano/codigo.mjs <id do pedido> Completo

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
uma Conta, todos com a mesma senha, e o caixa dele precisa estar fechado. A Conta precisa do plano
completo ativado pelo [pedido de plano](#pedido-de-plano-e-código-de-ativação), porque a carga
confere o relatório do dia e usa o estoque quando ele está ligado; sem o recurso, ela para antes de
começar.
