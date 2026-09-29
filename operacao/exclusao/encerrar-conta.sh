#!/bin/sh
# Executar com o serviço parado, depois de conferir a identidade de quem pediu e retirar os
# segredos Pix da Conta no Northflank. O registro externo sai antes do DELETE: se o banco falhar,
# uma restauração posterior ainda cumpre o pedido autorizado.

set -eu
set -o pipefail

CONTA_ID="${1:?informe o UUID da Conta}"
ADMIN_ID="${2:?informe o UUID do ADMIN que pediu}"
: "${CAIXA_SIMPLES_ENCERRAR_BANCO:?informe o banco da Conta}"
: "${CAIXA_SIMPLES_REMOCOES_DESTINO:?informe o bucket externo das remoções}"
: "${CAIXA_SIMPLES_SEGREDOS_REMOVIDOS:?confirme os segredos removidos no Northflank}"

if [ "$CAIXA_SIMPLES_SEGREDOS_REMOVIDOS" != sim ]; then
    echo "Confirme a remoção dos segredos da Conta no Northflank." >&2
    exit 1
fi
for id in "$CONTA_ID" "$ADMIN_ID"; do
    if ! printf '%s\n' "$id" | grep -Eq '^[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$'; then
        echo "Informe UUIDs válidos." >&2
        exit 1
    fi
done

EXISTE=$(printf "SELECT count(*) FROM conta c WHERE c.id = :'conta_id'::uuid AND EXISTS (SELECT 1 FROM usuario u WHERE u.id = :'admin_id'::uuid AND u.conta_id = c.id AND u.ativo AND u.perfil = 'ADMIN');\n" \
    | psql --no-psqlrc --quiet --tuples-only --no-align \
        --dbname="$CAIXA_SIMPLES_ENCERRAR_BANCO" --set=conta_id="$CONTA_ID" --set=admin_id="$ADMIN_ID")
if [ "$EXISTE" != 1 ]; then
    echo "Conta ou ADMIN solicitante não encontrado; nenhuma remoção foi registrada." >&2
    exit 1
fi

TRABALHO=$(mktemp -d)
trap 'rm -rf "$TRABALHO"' EXIT
INSTANTE=$(date -u +%Y-%m-%dT%H:%M:%SZ)
NOME=$(date -u +%Y%m%dT%H%M%SZ)-$$.json
jq -n --arg contaId "$CONTA_ID" --arg registroId "$CONTA_ID" \
    --arg solicitadoPor "$ADMIN_ID" --arg instante "$INSTANTE" \
    '{contaId: $contaId, tipo: "CONTA", registroId: $registroId,
      instante: $instante, solicitadoPor: $solicitadoPor}' > "$TRABALHO/remocao.json"

rclone copyto --no-check-dest "$TRABALHO/remocao.json" \
    "$CAIXA_SIMPLES_REMOCOES_DESTINO/$CONTA_ID/CONTA/$CONTA_ID/$NOME"

AQUI=$(dirname "$0")
psql --no-psqlrc --quiet --dbname="$CAIXA_SIMPLES_ENCERRAR_BANCO" \
    --set=conta_id="$CONTA_ID" --file="$AQUI/apagar-conta.sql"

RESTANTE=$(printf "SELECT count(*) FROM conta WHERE id = :'conta_id'::uuid;\n" \
    | psql --no-psqlrc --quiet --tuples-only --no-align \
        --dbname="$CAIXA_SIMPLES_ENCERRAR_BANCO" --set=conta_id="$CONTA_ID")
if [ "$RESTANTE" != 0 ]; then
    echo "A Conta ainda existe; mantenha o serviço parado e investigue." >&2
    exit 1
fi
echo "Conta encerrada; mantenha a prova externa de remoção."
