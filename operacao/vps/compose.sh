#!/bin/sh
# O compose da produção, sempre com a configuração da máquina e a versão no ar. Todo comando de
# operação na VPS passa por aqui: sem os dois arquivos, o Compose interpolaria tudo vazio.
#
# Uso: sh compose.sh <argumentos do docker compose>, por exemplo sh compose.sh ps
#
# CAIXA_SIMPLES_COMPOSE_ADICIONAL acrescenta um arquivo por cima deste: o do ensaio local,
# operacao/ensaio/compose.yaml, com CAIXA_SIMPLES_CONFIGURACAO e CAIXA_SIMPLES_ESTADO apontando para
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
CAIXA_SIMPLES_CONFIGURACAO=${CAIXA_SIMPLES_CONFIGURACAO:-/etc/caixa-simples}
export CAIXA_SIMPLES_CONFIGURACAO
VERSAO=${CAIXA_SIMPLES_ESTADO:-/var/lib/caixa-simples}/versao.env

if [ ! -f "$CAIXA_SIMPLES_CONFIGURACAO/caixa-simples.env" ]; then
    echo "Falta $CAIXA_SIMPLES_CONFIGURACAO/caixa-simples.env; gere-o com criar-configuracao.sh." >&2
    exit 1
fi
# Antes do primeiro deploy não há versão no ar. Quem pede a versão nova, o deploy, a passa pelo
# ambiente, que vale mais que o arquivo.
if [ ! -f "$VERSAO" ] && [ -z "${CAIXA_SIMPLES_VERSAO:-}" ]; then
    echo "Nenhuma versão no ar ainda: o primeiro deploy grava $VERSAO." >&2
    exit 1
fi
if [ -f "$VERSAO" ]; then
    set -- --env-file "$VERSAO" "$@"
fi
if [ -n "${CAIXA_SIMPLES_COMPOSE_ADICIONAL:-}" ]; then
    set -- --file "$CAIXA_SIMPLES_COMPOSE_ADICIONAL" "$@"
fi

exec docker compose --project-directory "$AQUI" --file "$AQUI/compose.yaml" \
    --env-file "$CAIXA_SIMPLES_CONFIGURACAO/caixa-simples.env" "$@"
