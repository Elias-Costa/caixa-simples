#!/bin/sh
# O alerta de erros, a cada 5 minutos: conta no log da aplicação as linhas em ERROR dos últimos 5 minutos e,
# a partir de uma, manda o sinal de falha ao vigia, que avisa o mantenedor; sem nenhuma, o sinal normal.
# Entram o erro inesperado, que responde 500 e sai com a pilha; toda resposta 5xx, como a indisponibilidade
# do Pix ou do registro de remoções; a recusa inesperada de um gesto do lote, que responde 200; e o erro que o
# aplicativo relata. Um erro só já é um defeito numa rota que alguém usou, e sem o alerta ele só chegaria ao
# mantenedor pelo relato do cliente. O sinal é um GET sem corpo: nenhuma linha do log sai da máquina.
#
# Sem conseguir ler o log, o script não manda sinal nenhum, e o check só cai depois da tolerância: assim a
# troca da aplicação no deploy não vira alerta.
#
# Uso: sh vigia-de-erros.sh, pelo timer ecrx-caixa-erros.
set -u

CONFIGURACAO=${ECRX_CAIXA_CONFIGURACAO:-/etc/ecrx-caixa}
LIMITE=1
AQUI=$(cd "$(dirname "$0")" && pwd)
vigia=$(sed -n 's/^ECRX_CAIXA_VIGIA_ERROS=//p' "$CONFIGURACAO/ecrx-caixa.env")

if [ -z "$vigia" ]; then
    echo "Falta ECRX_CAIXA_VIGIA_ERROS na configuração." >&2
    exit 1
fi

log=$(mktemp)
trap 'rm -f "$log"' EXIT
if ! sh "$AQUI/compose.sh" logs --no-color --no-log-prefix --since 5m app > "$log"; then
    echo "O log da aplicação não pôde ser lido; nenhum sinal enviado." >&2
    exit 1
fi

# A linha do registro começa pelo instante e traz o nível logo depois; as linhas da pilha que seguem um
# erro não começam pelo instante e não contam.
erros=$(grep -cE '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[^ ]+ +ERROR ' "$log")
echo "$erros linhas de erro nos últimos 5 minutos, com alerta a partir de $LIMITE."

if [ "$erros" -ge "$LIMITE" ]; then
    resultado=1
    sinal="$vigia/fail"
else
    resultado=0
    sinal=$vigia
fi
curl --fail --silent --show-error --max-time 10 --retry 3 --output /dev/null "$sinal" \
    || echo "O vigia não recebeu o sinal dos erros." >&2
exit "$resultado"
