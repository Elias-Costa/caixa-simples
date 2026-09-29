#!/bin/sh
# Restaura uma cópia cifrada num PostgreSQL vazio e confere o resultado contra a conferência
# gravada junto com ela, na origem, na hora da cópia.
#
# Uso: restaurar.sh <cópia sem a extensão, no formato do rclone>
#   restaurar.sh s3:bucket/diaria/caixa-simples-20261027T060012Z
#   restaurar.sh /copias/horaria/caixa-simples-20261027T150008Z
#
# A credencial que busca a cópia é de leitura, e não a do job, que só grava.
#
# Variáveis:
#   CAIXA_SIMPLES_COPIA_CHAVE_PRIVADA   caminho do arquivo com a chave privada do age
#   CAIXA_SIMPLES_RESTAURAR_BANCO       URL de conexão do banco vazio que recebe a cópia
#   CAIXA_SIMPLES_REMOCOES_DESTINO      registro externo, separado das cópias
#
# Termina com "confere" e código 0, ou com a diferença e código 1.

set -eu
set -o pipefail

COPIA="${1:?informe a cópia, sem a extensão}"
: "${CAIXA_SIMPLES_COPIA_CHAVE_PRIVADA:?informe o arquivo da chave privada do age}"
: "${CAIXA_SIMPLES_RESTAURAR_BANCO:?informe a URL de conexão do banco que recebe a cópia}"
: "${CAIXA_SIMPLES_REMOCOES_DESTINO:?informe o destino externo das remoções}"

AQUI=$(dirname "$0")
TRABALHO=$(mktemp -d)
trap 'rm -rf "$TRABALHO"' EXIT

# Restaurar por cima de dados existentes misturaria duas histórias numa só.
TABELAS=$(psql --no-psqlrc --quiet --tuples-only --no-align --dbname="$CAIXA_SIMPLES_RESTAURAR_BANCO" \
    --command="SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public'")
if [ "$TABELAS" != 0 ]; then
    echo "O banco de destino tem $TABELAS tabelas no schema public; a restauração pede um banco vazio." >&2
    exit 1
fi

rclone copyto "$COPIA.dump.age" "$TRABALHO/copia.dump.age"
rclone copyto "$COPIA.conferencia.age" "$TRABALHO/copia.conferencia.age"

# Decifrado direto para o pg_restore, sem dump em claro no disco. Sem dono nem permissões da origem,
# porque o banco de destino tem outros usuários.
age --decrypt --identity "$CAIXA_SIMPLES_COPIA_CHAVE_PRIVADA" "$TRABALHO/copia.dump.age" \
    | pg_restore --no-owner --no-privileges --exit-on-error --dbname="$CAIXA_SIMPLES_RESTAURAR_BANCO"

ESPERADA=$(age --decrypt --identity "$CAIXA_SIMPLES_COPIA_CHAVE_PRIVADA" "$TRABALHO/copia.conferencia.age")
OBTIDA=$(psql --no-psqlrc --quiet --dbname="$CAIXA_SIMPLES_RESTAURAR_BANCO" --file="$AQUI/conferencia.sql")

if [ "$ESPERADA" = "$OBTIDA" ]; then
    printf '%s\n' "$OBTIDA"
    sh "$AQUI/reaplicar-remocoes.sh"
    echo "confere"
    exit 0
fi

printf '%s\n' "$ESPERADA" > "$TRABALHO/origem.txt"
printf '%s\n' "$OBTIDA" > "$TRABALHO/restaurado.txt"
echo "não confere: linhas com < são da origem, com > do banco restaurado" >&2
diff "$TRABALHO/origem.txt" "$TRABALHO/restaurado.txt" >&2 || true
exit 1
