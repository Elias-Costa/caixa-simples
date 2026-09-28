#!/bin/sh
# Cópia do banco, de hora em hora, cifrada antes de sair do contêiner.
#
# O dump vai do pg_dump direto para o age, e nenhum arquivo em claro chega ao disco. O job só tem
# a chave pública: cifra, mas não consegue decifrar o que guardou. Junto vai a conferência
# (contagem por tabela e totais por dia), também cifrada, contra a qual a restauração confere.
#
# O pg_dump lê um retrato consistente do banco, mas a conferência é lida antes e depois dele, em
# outras transações. Se as duas leituras diferem, houve escrita no meio, a conferência pode não
# corresponder ao dump, e a cópia é refeita.
#
# A cópia que começa na hora UTC de CAIXA_SIMPLES_COPIA_HORA_DIARIA vai para o destino diário,
# guardado por mais tempo; as das outras horas vão para o destino de hora em hora. Cada destino
# apaga o que é antigo pela regra de expiração do próprio armazenamento: este job não apaga nada.
#
# O envio só grava nome novo. Com If-None-Match, o armazenamento recusa gravar por cima de uma
# cópia que já existe, e a credencial do job não pode mais nada além de gravar desse jeito: nem
# ler, nem listar, nem apagar. Por isso o rclone não confere o destino antes de enviar, não lê o
# objeto depois e não pede permissão de acesso.
#
# Variáveis:
#   CAIXA_SIMPLES_COPIA_BANCO           URL de conexão do PostgreSQL de origem
#   CAIXA_SIMPLES_COPIA_CHAVE_PUBLICA   chave pública do age, a linha que começa com age1
#   CAIXA_SIMPLES_COPIA_DESTINO         destino das cópias de hora em hora, no formato do rclone
#                                       (s3:bucket/horaria ou /copias/horaria)
#   CAIXA_SIMPLES_COPIA_DESTINO_DIARIA  destino da cópia diária, no mesmo formato
#   CAIXA_SIMPLES_COPIA_HORA_DIARIA     hora UTC, de 0 a 23, cuja cópia vai para o destino diário

set -eu
set -o pipefail

: "${CAIXA_SIMPLES_COPIA_BANCO:?informe a URL de conexão do banco de origem}"
: "${CAIXA_SIMPLES_COPIA_CHAVE_PUBLICA:?informe a chave pública do age}"
: "${CAIXA_SIMPLES_COPIA_DESTINO:?informe o destino das cópias de hora em hora, no formato do rclone}"
: "${CAIXA_SIMPLES_COPIA_DESTINO_DIARIA:?informe o destino da cópia diária, no formato do rclone}"
: "${CAIXA_SIMPLES_COPIA_HORA_DIARIA:?informe a hora UTC da cópia diária, de 0 a 23}"

case "$CAIXA_SIMPLES_COPIA_HORA_DIARIA" in
    [0-9] | 1[0-9] | 2[0-3]) ;;
    *)
        echo "CAIXA_SIMPLES_COPIA_HORA_DIARIA deve ser uma hora UTC de 0 a 23, sem zero à esquerda." >&2
        exit 1
        ;;
esac

# O destino é decidido pela hora em que a cópia começa, e não pela hora em que termina, para que
# uma cópia repetida por causa da conferência continue indo para o mesmo lugar.
HORA_UTC=$(date -u +%H)
if [ "$HORA_UTC" -eq "$CAIXA_SIMPLES_COPIA_HORA_DIARIA" ]; then
    DESTINO=$CAIXA_SIMPLES_COPIA_DESTINO_DIARIA
else
    DESTINO=$CAIXA_SIMPLES_COPIA_DESTINO
fi

AQUI=$(dirname "$0")
TENTATIVAS=3
TRABALHO=$(mktemp -d)
trap 'rm -rf "$TRABALHO"' EXIT

conferir() {
    psql --no-psqlrc --quiet --dbname="$CAIXA_SIMPLES_COPIA_BANCO" --file="$AQUI/conferencia.sql"
}

enviar() {
    rclone copyto --no-check-dest --s3-no-head --s3-no-check-bucket \
        --header-upload "If-None-Match: *" "$1" "$2"
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
enviar "$TRABALHO/copia.dump.age" "$DESTINO/$NOME.dump.age"
enviar "$TRABALHO/copia.conferencia.age" "$DESTINO/$NOME.conferencia.age"

TAMANHO=$(wc -c < "$TRABALHO/copia.dump.age" | tr -d ' ')
echo "Cópia $NOME enviada para $DESTINO: $TAMANHO bytes cifrados, conferência na tentativa $tentativa."
