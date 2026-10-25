#!/bin/sh
# O compose da produção, sempre com a configuração da máquina e a versão no ar. Todo comando de
# operação na VPS passa por aqui: sem os dois arquivos, o Compose interpolaria tudo vazio.
#
# Uso: sh compose.sh <argumentos do docker compose>, por exemplo sh compose.sh ps
#
# FECHADIA_COMPOSE_ADICIONAL acrescenta um arquivo por cima deste: o do ensaio local,
# operacao/ensaio/compose.yaml, com FECHADIA_CONFIGURACAO e FECHADIA_ESTADO apontando para
# pastas fora do repositório, para o ensaio rodar os mesmos scripts que a máquina; ou, na máquina, um
# temporário, como o que liga o access log para medir a borda.
set -eu

# No Git Bash do Windows, onde o ensaio roda, sem esta variável os caminhos de dentro do contêiner
# passados ao compose, como /etc/caddy/Caddyfile, viram caminhos do Windows antes de chegar ao
# Docker; e o Docker precisa desta pasta no formato C:/..., que só o pwd -W do Git Bash dá. No
# Linux, nenhuma das duas coisas tem efeito.
export MSYS_NO_PATHCONV=1
AQUI=$(cd "$(dirname "$0")" && (pwd -W 2>/dev/null || pwd))
# O compose também lê esta variável, para achar o arquivo das credenciais Pix e a pasta dos
# certificados.
FECHADIA_CONFIGURACAO=${FECHADIA_CONFIGURACAO:-/etc/fechadia}
export FECHADIA_CONFIGURACAO
VERSAO=${FECHADIA_ESTADO:-/var/lib/fechadia}/versao.env

if [ ! -f "$FECHADIA_CONFIGURACAO/fechadia.env" ]; then
    echo "Falta $FECHADIA_CONFIGURACAO/fechadia.env; gere-o com criar-configuracao.sh." >&2
    exit 1
fi
# Antes do primeiro deploy não há versão no ar. Quem pede a versão nova, o deploy, a passa pelo
# ambiente, que vale mais que o arquivo.
if [ ! -f "$VERSAO" ] && [ -z "${FECHADIA_VERSAO:-}" ]; then
    echo "Nenhuma versão no ar ainda: o primeiro deploy grava $VERSAO." >&2
    exit 1
fi
if [ -f "$VERSAO" ]; then
    set -- --env-file "$VERSAO" "$@"
fi
if [ -n "${FECHADIA_COMPOSE_ADICIONAL:-}" ]; then
    set -- --file "$FECHADIA_COMPOSE_ADICIONAL" "$@"
fi

exec docker compose --project-directory "$AQUI" --file "$AQUI/compose.yaml" \
    --env-file "$FECHADIA_CONFIGURACAO/fechadia.env" "$@"
