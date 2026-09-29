#!/bin/sh
# O banco restaurado fica sem tráfego até este script terminar. Cada registro externo é
# reaplicado mesmo quando a cópia já contém o efeito; as escritas são idempotentes.

set -eu
set -o pipefail

: "${CAIXA_SIMPLES_REMOCOES_DESTINO:?informe o destino externo das remoções}"
: "${CAIXA_SIMPLES_RESTAURAR_BANCO:?informe o banco restaurado}"

AQUI=$(dirname "$0")
COLUNA=$(psql --no-psqlrc --quiet --tuples-only --no-align \
    --dbname="$CAIXA_SIMPLES_RESTAURAR_BANCO" \
    --command="SELECT count(*) FROM information_schema.columns WHERE table_name = 'cliente' AND column_name = 'removido_em'")
if [ "$COLUNA" != 1 ]; then
    echo "Aplique as migrations no banco isolado antes de reaplicar as remoções." >&2
    exit 1
fi

rclone lsf --recursive --files-only --include '*.json' "$CAIXA_SIMPLES_REMOCOES_DESTINO" \
    | while IFS= read -r chave; do
        [ -n "$chave" ] || continue
        registro=$(rclone cat "$CAIXA_SIMPLES_REMOCOES_DESTINO/$chave")
        conta_id=$(printf '%s' "$registro" | jq -er '.contaId | strings')
        tipo=$(printf '%s' "$registro" | jq -er '.tipo | strings')
        registro_id=$(printf '%s' "$registro" | jq -er '.registroId | strings')
        instante=$(printf '%s' "$registro" | jq -er '.instante | strings')
        case "$tipo" in
            CLIENTE) arquivo="$AQUI/reaplicar-cliente.sql" ;;
            USUARIO) arquivo="$AQUI/reaplicar-usuario.sql" ;;
            NOME_USUARIO) arquivo="$AQUI/reaplicar-nome-usuario.sql" ;;
            CONTA) arquivo="$AQUI/../exclusao/apagar-conta.sql" ;;
            *) echo "Tipo de remoção desconhecido." >&2; exit 1 ;;
        esac
        psql --no-psqlrc --quiet --dbname="$CAIXA_SIMPLES_RESTAURAR_BANCO" \
            --set=conta_id="$conta_id" --set=registro_id="$registro_id" \
            --set=instante="$instante" --file="$arquivo"
    done
