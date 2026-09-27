#!/bin/sh
# Cópia diária do banco, cifrada antes de sair do contêiner.
#
# O dump vai do pg_dump direto para o age, e nenhum arquivo em claro chega ao disco. O job só tem
# a chave pública: cifra, mas não consegue decifrar o que guardou. Junto vai a conferência
# (contagem por tabela e totais por dia), também cifrada, contra a qual a restauração confere.
#
# O pg_dump lê um retrato consistente do banco, mas a conferência é lida antes e depois dele, em
# outras transações. Se as duas leituras diferem, houve escrita no meio, a conferência pode não
# corresponder ao dump, e a cópia é refeita.
#
# Variáveis:
#   CAIXA_SIMPLES_COPIA_BANCO           URL de conexão do PostgreSQL de origem
#   CAIXA_SIMPLES_COPIA_CHAVE_PUBLICA   chave pública do age, a linha que começa com age1
#   CAIXA_SIMPLES_COPIA_DESTINO         pasta de destino, no formato do rclone (r2:bucket ou /pasta)

set -eu
set -o pipefail

: "${CAIXA_SIMPLES_COPIA_BANCO:?informe a URL de conexão do banco de origem}"
: "${CAIXA_SIMPLES_COPIA_CHAVE_PUBLICA:?informe a chave pública do age}"
: "${CAIXA_SIMPLES_COPIA_DESTINO:?informe o destino no formato do rclone}"

AQUI=$(dirname "$0")
TENTATIVAS=3
TRABALHO=$(mktemp -d)
trap 'rm -rf "$TRABALHO"' EXIT

conferir() {
    psql --no-psqlrc --quiet --dbname="$CAIXA_SIMPLES_COPIA_BANCO" --file="$AQUI/conferencia.sql"
}

tentativa=1
while :; do
    # A conferência fica em variável, e não em arquivo: é dado de negócio, e só sai daqui cifrada.
    antes=$(conferir)
    pg_dump --format=custom --dbname="$CAIXA_SIMPLES_COPIA_BANCO" \
        | age --recipient "$CAIXA_SIMPLES_COPIA_CHAVE_PUBLICA" > "$TRABALHO/copia.dump.age"
    depois=$(conferir)
    if [ "$antes" = "$depois" ]; then
        break
    fi
    if [ "$tentativa" -ge "$TENTATIVAS" ]; then
        echo "O banco mudou durante cada uma das $TENTATIVAS tentativas; nada foi enviado." >&2
        exit 1
    fi
    echo "O banco mudou durante a tentativa $tentativa; refazendo a cópia." >&2
    tentativa=$((tentativa + 1))
done

printf '%s\n' "$depois" \
    | age --recipient "$CAIXA_SIMPLES_COPIA_CHAVE_PUBLICA" > "$TRABALHO/copia.conferencia.age"

# O nome carrega o instante em UTC, então a ordem alfabética é a cronológica. A conferência sobe
# por último: o par só está completo quando ela existe.
NOME="caixa-simples-$(date -u +%Y%m%dT%H%M%SZ)"
rclone copyto "$TRABALHO/copia.dump.age" "$CAIXA_SIMPLES_COPIA_DESTINO/$NOME.dump.age"
rclone copyto "$TRABALHO/copia.conferencia.age" "$CAIXA_SIMPLES_COPIA_DESTINO/$NOME.conferencia.age"

TAMANHO=$(wc -c < "$TRABALHO/copia.dump.age" | tr -d ' ')
echo "Cópia $NOME enviada: $TAMANHO bytes cifrados, conferência na tentativa $tentativa."
