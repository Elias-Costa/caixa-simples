# Operação

Tudo o que põe o sistema no ar e o mantém de pé: a imagem, o CI que publica e faz o deploy, a VPS e
o preparo dela, a cópia cifrada de hora em hora, a restauração, a recriação da máquina, a carga de
correção e o ensaio local de tudo isso.

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
| [`vps/`](vps/) | A VPS: o compose da produção, o proxy, o preparo da máquina, a configuração, o deploy, a cópia agendada, o vigia e as tarefas do systemd |
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

As bases de avisos ficam no volume `caixa-simples-trivy`, e os builds usam `--pull`, então as
imagens base guardadas na máquina são atualizadas a cada execução.

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
pacote para a ferramenta conferir; e, nas duas imagens deste repositório, a base examinada é a da tag
no dia da verificação, que pode mudar até o build da publicação. Trocar a versão da ferramenta é trocar
o digest no script, conferido em mais de um registro.

## A VPS

Uma máquina só, com AlmaLinux 9, 1 vCPU e 2 GB, e tudo em contêineres pelo
[`compose.yaml`](vps/compose.yaml):

| Serviço | O que é | Memória |
|---|---|---|
| `proxy` | Caddy, preso por digest: termina o HTTPS, com o certificado obtido e renovado por ele, e encaminha à aplicação. O único com porta publicada, a 80 e a 443 | 128 MB |
| `app` | A aplicação, da imagem do commit no ar, sem porta publicada | 1 GB |
| `banco` | PostgreSQL 17, da imagem construída de [`vps/banco/`](vps/banco/Dockerfile), sem porta publicada, com os dados num volume | 512 MB |
| `seed` | Perfil `seed`: cria uma Conta e termina | 1 GB |
| `copia` | Perfil `copia`: a cópia cifrada, pelo timer de hora em hora | 256 MB |
| `restaurar` | Perfil `restauracao`: restaura uma cópia num banco vazio, por padrão o desta máquina | |
| `encerrar` | Perfil `exclusao`: encerra uma Conta a pedido | |

Nenhum contêiner usa swap, nos limites em que a memória foi medida: a JVM com o heap em disco
travaria. Somados, os limites e o sistema passam um pouco de 2 GB, e o swap de 1 GB da máquina socorre
o sistema e o Docker num pico. A CPU não tem limite por serviço. O Docker abre a porta publicada por
fora do firewall da máquina, e por isso só o proxy publica alguma.

Na máquina, fora dos contêineres:

| Caminho ou unidade | O que é |
|---|---|
| `/opt/caixa-simples/repositorio` | Clone deste repositório público, no commit no ar; é dele que saem o compose e os scripts |
| `/etc/caixa-simples/caixa-simples.env` | A [configuração](#configuração), só do root |
| `/etc/caixa-simples/efi.env` e `efi/` | As credenciais e os certificados Pix de cada Conta |
| `/var/lib/caixa-simples/versao.env` | O commit no ar, gravado pelo deploy |
| `/usr/local/sbin/caixa-simples-implantar` | O script de deploy, cópia de [`vps/implantar.sh`](vps/implantar.sh) feita pelo preparo, fora do repositório, para um commit não trocar as regras do próprio deploy |
| `caixa-simples-copia.timer` | A [cópia](#cópia-de-hora-em-hora-cifrada), toda hora cheia, em UTC |
| `caixa-simples-vigia.timer` | O sinal de vida para o [vigia](#vigia), a cada 5 minutos |
| `dnf-automatic.timer` | As atualizações de segurança, às 7h30 UTC, dentro da janela, com reinício quando pedido |

O [preparo](vps/preparar.sh) deixa o SSH só por chave e sem root, o firewall só com SSH, HTTP e HTTPS,
o fuso em UTC, o swap de 1 GB, e os logs do sistema e dos contêineres no journald, um arquivo por dia,
apagados depois de 7 dias, com teto de 1 GB: os logs podem levar o endereço de quem acessa. O usuário
`implantacao` existe só para o CI: a chave dele, com `restrict` e um comando forçado, só roda o script
de deploy, e o sudo só lhe deixa rodar esse script, sem argumento, recebendo o SHA pelo ambiente.

**Na VPS, os comandos são como root** (`sudo -i`). Todo comando do compose passa por
[`compose.sh`](vps/compose.sh), que acrescenta a configuração e a versão no ar:

```bash
cd /opt/caixa-simples/repositorio/operacao/vps
sh compose.sh ps
journalctl CONTAINER_NAME=caixa-simples-app-1 --since -1h
```

Rodar o preparo de novo refaz cada passo sem duplicar nada. Mudou o `implantar.sh`, rode-o de novo:
é ele que copia o script de deploy para fora do repositório.

## Configuração

O arquivo `/etc/caixa-simples/caixa-simples.env` é gerado uma vez por
[`criar-configuracao.sh`](vps/criar-configuracao.sh), que o preparo chama: os segredos que nascem na
máquina saem aleatórios, e o resto fica vazio, para preencher à mão. Ele nunca sobrescreve um arquivo
que já existe. Valores sem aspas e sem espaço em volta do sinal de igual. Cada contêiner recebe só as
variáveis que o compose lista para ele.

| Variável | De onde vem | Para quê |
|---|---|---|
| `CAIXA_SIMPLES_DOMINIO` | O domínio, com o registro A para a VPS | O site do proxy, o certificado e a conferência do vigia |
| `CAIXA_SIMPLES_DB_PASSWORD` | Gerada | A senha do banco, para a aplicação, a cópia e a restauração. Trocá-la depois exige trocá-la também dentro do banco |
| `CAIXA_SIMPLES_JWT_SECRET` | Gerada | Assinatura dos tokens, com ao menos 32 bytes. Trocá-la obriga todo mundo a entrar de novo |
| `CAIXA_SIMPLES_PLANO_SECRET` | Gerado; guarde também fora da máquina | Confere o código de ativação de cada pedido de plano; ver [Pedido de plano](#pedido-de-plano-e-código-de-ativação). A aplicação não sobe sem ele |
| `CAIXA_SIMPLES_MENSALIDADE_CAIXA_SIMPLES` e `CAIXA_SIMPLES_MENSALIDADE_COMPLETO` | À mão, em reais, com ponto decimal; a do completo maior | Valor dos pedidos de plano. A aplicação não sobe sem elas |
| `CAIXA_SIMPLES_REMOCOES_BUCKET` e `CAIXA_SIMPLES_REMOCOES_AWS_*` | O bucket de remoções e a credencial que só grava nele | O registro mínimo antes de uma exclusão; ver [Remoção](#remoção-de-dados-e-encerramento-da-conta) |
| `CAIXA_SIMPLES_REMOCOES_DESTINO` | `s3:<bucket de remoções>/remocoes` | O encerramento grava ali, e a restauração lê dali |
| `CAIXA_SIMPLES_COPIA_CHAVE_PUBLICA` | A linha `age1...` da chave pública | A cópia é cifrada para ela |
| `CAIXA_SIMPLES_COPIA_DESTINO` e `CAIXA_SIMPLES_COPIA_DESTINO_DIARIA` | `s3:<bucket>/horaria` e `s3:<bucket>/diaria` | Os dois destinos da cópia |
| `CAIXA_SIMPLES_COPIA_HORA_DIARIA` | `6` | A hora UTC da cópia que fica 30 dias |
| `CAIXA_SIMPLES_COPIA_AWS_*` | A credencial do usuário do IAM da cópia | Grava objeto novo no bucket, e mais nada |
| `CAIXA_SIMPLES_VIGIA_COPIA` e `CAIXA_SIMPLES_VIGIA_MAQUINA` | Os endereços de sinal dos dois checks | O [vigia](#vigia) |

`/etc/caixa-simples/efi.env` guarda, para cada Conta que recebe Pix, com o UUID da Conta em
maiúsculas e sem hífens, `CAIXA_SIMPLES_EFI_<UUID>_AMBIENTE`, `_CLIENT_ID`, `_CLIENT_SECRET`,
`_CHAVE_PIX`, `_CERTIFICADO_P12`, `_CERTIFICADO_SENHA`, `_WEBHOOK_ID` e `_WEBHOOK_SECRET`. O
certificado `.p12` vai em `/etc/caixa-simples/efi/<uuid da Conta>.p12`, com dono 10001, e
`_CERTIFICADO_P12` aponta para `/segredos/efi/<uuid da Conta>.p12`, onde a pasta aparece dentro da
aplicação, só para leitura.

Uma mudança na configuração vale quando o contêiner é recriado: `sh compose.sh up -d --wait` recria só
os que mudaram, e a troca da aplicação para o serviço, então vai na janela.

## Primeira publicação

1. **Contratar a VPS** com AlmaLinux 9, sem painel. Antes de qualquer Conta real, confirmar por escrito
   com o fornecedor onde a máquina fica e se há backup dele; até a resposta, só Contas de teste.
2. **Domínio:** o registro A para o IP da VPS, sem registro AAAA, porque por IPv6 o encaminhamento de
   porta do Docker não preserva o endereço de origem.
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
   [Cópia de hora em hora](#cópia-de-hora-em-hora-cifrada), e o bucket separado de remoções, com
   credencial de gravação para a aplicação e leitura para a restauração.
7. **Vigia:** os dois checks, como em [Vigia](#vigia).
8. **Configuração:** preencher `/etc/caixa-simples/caixa-simples.env`.
9. **GitHub**, nas configurações do repositório:
   - o segredo `VPS_CHAVE_DE_IMPLANTACAO`, com o conteúdo da chave privada do CI;
   - a variável `VPS_ENDERECO`, com o IP ou o domínio da VPS;
   - a variável `VPS_CHAVE_DO_HOST`, com a chave pública do host no formato
     `ssh-ed25519 AAAA...`, lida na própria VPS em `/etc/ssh/ssh_host_ed25519_key.pub`, sem o
     comentário do fim;
   - a variável `PUBLICAR_NA_VPS`, com `true`.
10. **Imagens:** um push no `main` publica as duas imagens. Na primeira vez, conferir na página dos
    pacotes do GitHub que os dois são públicos: a VPS os baixa sem credencial.
11. **Primeiro deploy**, pelo botão **Run workflow**. Sem versão no ar ainda, ele vai a qualquer hora.
    A aplicação sobe, aplica as migrations, e o proxy obtém o certificado do domínio.
12. **Tarefas agendadas:** `systemctl start caixa-simples-copia.timer caixa-simples-vigia.timer`.
13. **Memória:** `sh compose.sh exec app cat /sys/fs/cgroup/memory.max` tem de dar 1073741824, o
    limite em que a JVM foi medida.
14. **Borda:** conferir pelo access log, como em [Borda](#borda).
15. **Contas de teste** pelo [seed](#criar-uma-conta), com o plano completo ativado pelo
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

O proxy não tem access log: a aplicação não registra cada requisição, e o endereço de quem acessa fica
fora dos logs, a não ser em recusa e erro.

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

**Na VPS**, como root, com a senha lida sem eco, para não ficar no histórico do shell:

```bash
export CAIXA_SIMPLES_SEED_NOME_NEGOCIO="Nome do negócio" CAIXA_SIMPLES_SEED_EMAIL=<e-mail do administrador>
read -rs CAIXA_SIMPLES_SEED_SENHA && export CAIXA_SIMPLES_SEED_SENHA
sh compose.sh --profile seed run --rm seed
unset CAIXA_SIMPLES_SEED_SENHA
```

O log termina com `Conta <id> criada`. A senha passa pela mesma política de qualquer senha, inclusive
a verificação de vazamento.

**No ensaio local**, pelo mesmo serviço; ver [Ensaio local](#ensaio-local).

## Pedido de plano e código de ativação

Toda Conta nasce no plano gratuito. O administrador pede a adesão, o upgrade ou a renovação na tela
Plano do aplicativo, manda o texto do pedido pelo canal de atendimento e faz o Pix do valor. O
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
abaixo, e depois **uma vez por mês**, anotando data, cópia usada, contagens, totais e resultado. O
registro externo das remoções fica em outro bucket, sem a expiração das cópias.

[`copia/restaurar.sh`](copia/restaurar.sh) busca o par, confere que o banco de destino está vazio,
decifra direto para o `pg_restore`, sem dump em claro no disco, e roda a conferência no banco
restaurado contra a que foi gravada na origem. Antes de liberar acesso, reaplica os registros de
remoção do bucket separado. Uma cópia anterior à coluna `cliente.removido_em` precisa receber as
migrations em ambiente isolado antes dessa reaplicação. Termina com `confere` e código 0, ou com a
diferença e código 1. A credencial de leitura e a chave privada entram só no comando, pelo ambiente e
por `-v`, e nunca ficam na máquina. Para ver as cópias, `rclone lsf s3:<bucket>/diaria/` com a
mesma credencial.

**Num PostgreSQL 17 local**, o vazio do ensaio, na máquina do mantenedor, com o
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
512 MB enquanto roda. Como root, na pasta `operacao/vps`, com a credencial de leitura exportada como
acima e a chave privada copiada só para este comando:

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

   O deploy sobe a aplicação sobre o banco restaurado e aplica as migrations que a cópia ainda não
   tem.
4. Apontar o registro A do domínio para o IP novo; o proxy obtém o certificado assim que o domínio
   chegar à máquina.
5. No GitHub, atualizar `VPS_ENDERECO` e `VPS_CHAVE_DO_HOST`.
6. `systemctl start caixa-simples-copia.timer caixa-simples-vigia.timer`, e apagar a chave privada da
   máquina.

No ensaio local, a recriação inteira, num projeto de compose novo com volume vazio, terminou com a
conferência em `confere`, a aplicação de pé em 43 segundos e o login de uma Conta que veio da cópia.

## Remoção de dados e encerramento da Conta

O ADMIN remove Cliente no aplicativo. Se houver dívida ou comanda ABERTA, a operação responde 409;
após quitar ou cancelar a Venda elegível, o pedido pode ser repetido. A inativação de Usuário apaga
a credencial, e o ADMIN pode anonimizar o nome depois. Essas ações gravam antes um registro mínimo
em objetos `remocoes/<conta>/<tipo>/<id>/<instante>.json` num bucket S3 separado do backup, na
região `sa-east-1`. O bucket de remoções não usa o ciclo de vida de três ou trinta dias das cópias.
A aplicação recebe `CAIXA_SIMPLES_REMOCOES_BUCKET` e uma credencial limitada a gravar nesse bucket.
Sem acesso ao registro, a API responde 503 e não remove o dado do banco.

O ADMIN pede encerramento pelo endereço publicado no aviso de privacidade. Antes de executar, confira
a identidade e o vínculo do ADMIN com a Conta, anote a data do pedido para cumprir o prazo de quinze
dias e pare a aplicação para impedir novas escritas, com `sh compose.sh stop app`. Retire de
`/etc/caixa-simples/efi.env` todas as linhas da Conta, que começam por
`CAIXA_SIMPLES_EFI_<UUID_DA_CONTA_SEM_HIFENS>_`, e apague o certificado dela em
`/etc/caixa-simples/efi/`. Confira que nenhuma linha com esse prefixo restou e guarde a evidência
dessa conferência fora do banco a apagar.

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

## Vigia

Dois checks no Healthchecks.io, plano gratuito, com aviso por e-mail ao mantenedor quando um sinal
esperado não chega ou quando chega o de falha:

| Check | Período | Tolerância | Quem manda o sinal |
|---|---|---|---|
| cópia | 1 hora | 30 minutos | [`copia-agendada.sh`](vps/copia-agendada.sh), depois de cada cópia: o sinal, ou o de falha |
| máquina | 5 minutos | 10 minutos | [`vigia.sh`](vps/vigia.sh), depois de conferir a aplicação pelo domínio, passando pelo proxy e pelo certificado |

O endereço de sinal de cada check vai na [configuração](#configuração). O sinal é um GET sem corpo, só
com o endereço do check: nenhum log e nada das Contas sai da máquina por ele. Cobrem a hora sem cópia,
a máquina ou a rede fora do ar, a aplicação parada ou travada e o certificado vencido. O deploy que
falha aparece no CI, que fica vermelho, e o GitHub avisa por e-mail.

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
sh operacao/vps/compose.sh --profile seed --profile copia --profile restauracao --profile exclusao down -v
```

No Git Bash do Windows, o `curl` do sistema confere a revogação do certificado, que a autoridade local
do proxy não publica: use `--ssl-no-revoke` junto de `--cacert`.

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
