# Operação

Tudo o que põe o sistema no ar e o mantém de pé: a imagem, o CI, o Blueprint do Render, a cópia
diária cifrada, a restauração, a carga de correção e o ensaio local de tudo isso.

| Arquivo | O que é |
|---|---|
| [`Dockerfile`](../Dockerfile) | Imagem do jar único, com a API e o aplicativo na mesma origem |
| [`.github/workflows/ci.yml`](../.github/workflows/ci.yml) | Testes Java e do front-end, build e lint, a cada push no `main` e a cada pull request |
| [`render.yaml`](../render.yaml) | Blueprint: o serviço web, o PostgreSQL e o job da cópia diária |
| [`copia/`](copia/) | Imagem do job, a cópia diária, a restauração e a conferência entre as duas |
| [`carga/carga.mjs`](carga/carga.mjs) | Carga de correção contra uma instalação |
| [`ensaio/compose.yaml`](ensaio/compose.yaml) | A produção em miniatura, na máquina local |

## Do push à produção

1. O push no `main` dispara o CI: `sh mvnw -B -ntp verify`, que roda os testes Java contra um
   PostgreSQL em contêiner, os testes e o build do front-end e gera o jar, e depois o lint do
   front-end.
2. O Render só constrói e publica o commit em que o CI passou (`autoDeployTrigger: checksPass`), a
   partir do `Dockerfile`. Commit que muda só o `README.md` ou `operacao/` não reconstrói o serviço
   web; o job da cópia só é reconstruído quando muda `operacao/copia/`.
3. A instância nova sobe antes de a antiga sair: aplica as migrations, valida o schema contra as
   entidades e só então passa no healthcheck e recebe tráfego.

**Migration só na janela de manutenção.** Todo push no `main` publica, então o commit com migration
nova só é enviado entre 0h e 5h no fuso America/Bahia (3h às 8h UTC): enquanto a instância nova
aplica o schema, a antiga continua servindo com o de antes. Deploy sem migration pode ir a qualquer
hora. A mesma janela vale para troca de plano do banco, restauração e qualquer manutenção que possa
derrubar o serviço.

## Imagem

Três estágios: o build, com o Maven e o Node que o `pom.xml` fixa, em imagem com glibc; a extração
do jar em camadas, das que mudam menos para as que mudam mais; e a execução, só com o JRE e sem root.
Os testes do front-end rodam no build da imagem, porque fazem parte do empacotamento. Os testes Java
não, porque sobem um PostgreSQL em contêiner e o build não tem Docker; quem os garante é o CI, antes
do deploy.

A JVM foi medida sob a carga de correção, no limite de 512 MB do menor plano:
`-XX:+UseSerialGC -XX:MaxRAMPercentage=45 -XX:+ExitOnOutOfMemoryError`, pelo `JAVA_TOOL_OPTIONS` da
imagem. O porquê de cada valor está no próprio `Dockerfile`. Um `JAVA_TOOL_OPTIONS` definido no
painel substitui o da imagem inteiro: repita os três valores e meça de novo com a carga.

## Variáveis do serviço web

| Variável | De onde vem | Para quê |
|---|---|---|
| `CAIXA_SIMPLES_DB_URL` | À mão | `jdbc:postgresql://<host interno do banco>:5432/caixa_simples` |
| `CAIXA_SIMPLES_DB_USER` e `CAIXA_SIMPLES_DB_PASSWORD` | Do banco, pelo Blueprint | Credencial do PostgreSQL |
| `CAIXA_SIMPLES_JWT_SECRET` | Gerada pelo Render na criação | Assinatura dos tokens, com ao menos 32 bytes. Trocá-la obriga todo mundo a entrar de novo |
| `CAIXA_SIMPLES_PORT` | Blueprint | 10000, a porta que o Render encaminha |
| `CAIXA_SIMPLES_PROXIES_CONFIAVEIS` | Opcional | Substitui a lista de faixas da borda; ver [Borda](#borda) |
| `CAIXA_SIMPLES_EFI_<UUID_DA_CONTA_SEM_HIFENS>_*` | À mão, por Conta que recebe Pix | `AMBIENTE`, `CLIENT_ID`, `CLIENT_SECRET`, `CHAVE_PIX`, `CERTIFICADO_P12`, `CERTIFICADO_SENHA`, `WEBHOOK_ID` e `WEBHOOK_SECRET` |

O certificado `.p12` de cada Conta entra como arquivo secreto do Render, que o expõe em
`/etc/secrets/<nome do arquivo>`; é esse caminho que vai em `CERTIFICADO_P12`.

## Primeira aplicação do Blueprint

1. No painel do Render, criar um Blueprint apontando para este repositório. O Render lê o
   `render.yaml`, cria os três recursos e pede os valores marcados com `sync: false`.
2. `CAIXA_SIMPLES_DB_URL` depende do host interno do banco, que só existe depois que o banco é
   criado. Preencha um valor provisório, espere o banco ficar disponível, copie o host interno da
   página dele, corrija a variável e publique de novo. O primeiro deploy do serviço web falha, e é
   esperado.
3. Preencher as variáveis da [cópia diária](#cópia-diária-cifrada) e, para cada Conta que recebe Pix,
   as da Efí.
4. Ligar as notificações do Render para falha de deploy e falha do job da cópia.
5. Conferir, pelo Shell do serviço, `cat /sys/fs/cgroup/memory.max` (em cgroup v1,
   `/sys/fs/cgroup/memory/memory.limit_in_bytes`): 536870912 é o limite em que a JVM foi medida. Um
   limite menor pede nova medição com a carga.

Um ajuste feito no painel num campo que o `render.yaml` declara é desfeito na sincronização seguinte
do Blueprint: a mudança vai no arquivo.

## Borda

Na frente da aplicação há dois proxies: a borda da Cloudflare, que termina o HTTPS, e o balanceador
do Render, que fala com a aplicação por HTTP na rede interna. Cada um acrescenta ao
`X-Forwarded-For` o endereço de quem se conectou a ele.

A aplicação lê esse cabeçalho da direita para a esquerda: descarta os saltos da rede interna e das
faixas publicadas da Cloudflare, e o primeiro endereço fora delas é a origem. O que o cliente
escreve à esquerda nunca é lido. O `X-Forwarded-Proto` diz se a requisição chegou por HTTPS, e é o
que faz a resposta levar o `Strict-Transport-Security`.

Se a Cloudflare publicar faixas novas, ou se a borda mudar, `CAIXA_SIMPLES_PROXIES_CONFIAVEIS`
substitui a lista inteira, em CIDRs separados por vírgula, sem novo build. Até lá, a origem passa a
ser o endereço da borda: a falha é fechada, e nenhum endereço escrito pelo cliente vira origem.

**Conferir depois de publicar.** A cadeia foi medida por terceiros e precisa ser confirmada no
serviço publicado. Ligue o access log do Tomcat pelas variáveis abaixo (cada mudança de variável
publica de novo), faça uma requisição de um aparelho cujo IP público você conhece e leia o arquivo
pelo Shell do serviço, com `cat /tmp/access_log.*.log`. O primeiro campo tem de ser o seu IP; o
segundo mostra a cadeia inteira, como chegou. Depois apague as variáveis.

```
SERVER_TOMCAT_ACCESSLOG_ENABLED=true
SERVER_TOMCAT_ACCESSLOG_DIRECTORY=/tmp
SERVER_TOMCAT_ACCESSLOG_REQUEST_ATTRIBUTES_ENABLED=true
SERVER_TOMCAT_ACCESSLOG_PATTERN=%a %{X-Forwarded-For}i %{X-Forwarded-Proto}i %r %s
```

## Healthcheck

O Render consulta `/actuator/health/liveness`, que responde `{"status":"UP"}` sem consultar o banco.
Com o banco fora do ar, a instância continua de pé e as requisições que dependem dele falham até ele
voltar; uma sonda que consultasse o banco faria o Render reiniciar em laço uma aplicação sem defeito,
sem trazer o banco de volta. A instância só começa a responder depois das migrations, então a sonda
sem banco não deixa passar no deploy uma instância que não alcança o banco.

`/actuator/health` consulta o banco e serve para conferência à mão. Nenhum outro endpoint do
Actuator é exposto: qualquer outro caminho sob `/actuator` recebe a página do aplicativo, como toda
rota desconhecida fora de `/api`.

## Criar uma Conta

Conta nasce pelo seed: uma execução avulsa da imagem, com o perfil `seed` e sem servidor web, que
cria a Conta com o administrador e termina. Nunca no serviço web. Rodar de novo com o mesmo e-mail
não cria nada.

**No Render**, por um job avulso (one-off job), criado pela API com uma chave de API da conta do
Render. Ele roda a mesma imagem, com as variáveis do serviço, em instância própria:

```bash
curl -X POST "https://api.render.com/v1/services/<id do serviço web>/jobs" \
  -H "Authorization: Bearer $RENDER_API_KEY" \
  -H "Content-Type: application/json" \
  -d @seed.json
```

com o `seed.json`:

```json
{
  "startCommand": "sh -c 'java -Dspring.profiles.active=seed -Dspring.main.web-application-type=none -Dcaixa-simples.seed.nome-negocio=\"<nome do negócio>\" -Dcaixa-simples.seed.email=<e-mail do administrador> -Dcaixa-simples.seed.senha=\"<senha inicial>\" -jar /aplicacao/aplicacao.jar'"
}
```

O log do job termina com `Conta <id> criada para <e-mail>`. O comando fica nos detalhes do job,
visível para quem acessa a conta do Render, que de todo modo já alcança o banco; apague o
`seed.json` depois. A senha passa pela mesma política de qualquer senha, inclusive a verificação de
vazamento.

**No ensaio local**, pelo serviço `seed` do compose; ver [Ensaio local](#ensaio-local).

## Cópia diária cifrada

Todo dia às 6h UTC (3h no fuso America/Bahia, dentro da janela), o job `caixa-simples-copia`:

1. lê a conferência: a contagem de linhas de cada tabela e os totais por dia das Vendas concluídas e
   dos movimentos de caixa, em [`copia/conferencia.sql`](copia/conferencia.sql);
2. roda o `pg_dump` direto para o `age`, cifrado para a chave pública do mantenedor. O dump em claro
   nunca chega ao disco, e o job não consegue decifrar o que guardou;
3. lê a conferência de novo. Se as duas leituras diferem, houve escrita no meio, e a cópia é
   refeita, até três vezes; depois da terceira, o job falha sem enviar nada;
4. envia `caixa-simples-<instante UTC>.dump.age` e, por último,
   `caixa-simples-<instante UTC>.conferencia.age`, também cifrada. A cópia só está completa com o
   par.

| Variável | Valor |
|---|---|
| `CAIXA_SIMPLES_COPIA_BANCO` | Do banco, pelo Blueprint: a URL de conexão interna |
| `CAIXA_SIMPLES_COPIA_CHAVE_PUBLICA` | A chave pública do age, a linha que começa com `age1` |
| `CAIXA_SIMPLES_COPIA_DESTINO` | `r2:<nome do bucket>` |
| `RCLONE_CONFIG_R2_TYPE`, `_PROVIDER` e `_NO_CHECK_BUCKET` | Fixas no Blueprint |
| `RCLONE_CONFIG_R2_ENDPOINT` | `https://<id da conta>.r2.cloudflarestorage.com` |
| `RCLONE_CONFIG_R2_ACCESS_KEY_ID` e `_SECRET_ACCESS_KEY` | Do token de API do bucket |

Preparação, uma vez:

1. **Chave.** Na máquina do mantenedor, `age-keygen -o caixa-simples-copia.txt`, ou pela própria
   imagem do job, construída pelo compose do ensaio, numa pasta fora do repositório:

   ```bash
   docker compose -f operacao/ensaio/compose.yaml --profile copia build copia
   docker run --rm -v "<pasta fora do repositório>:/chave" caixa-simples-copia:ensaio \
     age-keygen -o /chave/caixa-simples-copia.txt
   ```

   A linha `# public key: age1...` do arquivo vai para `CAIXA_SIMPLES_COPIA_CHAVE_PUBLICA`. O arquivo
   inteiro é a chave privada: fica num gerenciador de senhas e numa cópia offline, fora do Render e
   do Git. **Perdida a chave privada, nenhuma cópia pode ser lida, e não há como recuperá-la.**
2. **Bucket.** Um bucket só para as cópias no armazenamento de objetos da Cloudflare, com regra de
   ciclo de vida que apaga o que tem mais de 30 dias. O token de API tem leitura e escrita de
   objetos só nesse bucket, sem permissão de criar bucket; é por isso que o rclone não o confere
   (`NO_CHECK_BUCKET`).
3. **Aviso.** A notificação do Render para falha do job é o único sinal de que uma noite ficou sem
   cópia.

## Restauração

A primeira linha é a restauração para um instante passado do próprio Render, que cria um banco
novo. A cópia externa cobre o que ela não alcança: a perda da conta no Render e o erro notado tarde
demais.

[`copia/restaurar.sh`](copia/restaurar.sh) busca o par, confere que o banco de destino está vazio,
decifra direto para o `pg_restore`, sem dump em claro no disco, e roda a conferência no banco
restaurado contra a que foi gravada na origem. Termina com `confere` e código 0, ou com a diferença e
código 1.

Para restaurar a partir do bucket no PostgreSQL vazio do ensaio, com as credenciais de leitura do
bucket no ambiente:

```bash
docker compose -f operacao/ensaio/compose.yaml --profile restauracao run --rm \
  -e RCLONE_CONFIG_R2_TYPE=s3 -e RCLONE_CONFIG_R2_PROVIDER=Cloudflare \
  -e RCLONE_CONFIG_R2_ENDPOINT -e RCLONE_CONFIG_R2_ACCESS_KEY_ID -e RCLONE_CONFIG_R2_SECRET_ACCESS_KEY \
  -v "<arquivo da chave privada>:/chave/chave-privada.txt:ro" \
  restaurar r2:<nome do bucket>/caixa-simples-<instante UTC>
```

Para restaurar num banco novo do Render, o destino é a URL externa dele, com o IP de quem restaura
liberado temporariamente na lista de acesso do banco, que o Blueprint deixa vazia.

**Todo mês**, restaure a última cópia no ensaio local e anote data, cópia usada, contagens, totais e
resultado. Restauração nunca ensaiada não prova que a cópia serve.

## Ensaio local

A produção em miniatura: a imagem da aplicação com 0,5 CPU e 512 MB sem swap, como o menor plano,
contra um PostgreSQL 17, mais o seed, o job da cópia e um PostgreSQL vazio para a restauração.
Comandos a partir da raiz do repositório:

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

# Cópia para um volume do Docker, com um par de chaves só do ensaio, gerado como em
# "Cópia diária cifrada".
CAIXA_SIMPLES_COPIA_CHAVE_PUBLICA=<age1...> \
  docker compose -f operacao/ensaio/compose.yaml --profile copia run --rm --build copia

# Restauração da cópia no PostgreSQL vazio.
docker compose -f operacao/ensaio/compose.yaml --profile restauracao run --rm \
  -v "<arquivo da chave privada>:/chave/chave-privada.txt:ro" restaurar /copias/caixa-simples-<instante UTC>

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
