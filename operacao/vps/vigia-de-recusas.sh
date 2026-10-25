#!/bin/sh
# O alerta de rajada, a cada 5 minutos: conta no log da aplicação as recusas dos últimos 5 minutos, que
# são o login recusado, a troca recusada da senha, do e-mail ou da chave Pix, o token recusado, o acesso
# recusado por perfil ou pela senha provisória e o aviso Pix recusado. Acima de 50, manda o sinal de falha ao vigia, que avisa o
# mantenedor; até 50, o sinal normal. Uma origem sozinha não passa de 30 falhas de login na janela da
# contenção; o que chega a 50 é a força bruta distribuída, ou a enxurrada de token falso ou de aviso Pix,
# que nada limita. O sinal é um GET sem corpo: nenhuma linha do log sai da máquina.
#
# Sem conseguir ler o log, o script não manda sinal nenhum, e o check só cai depois da tolerância:
# assim a troca da aplicação no deploy não vira alerta.
#
# Uso: sh vigia-de-recusas.sh, pelo timer fechadia-recusas.
set -u

CONFIGURACAO=${FECHADIA_CONFIGURACAO:-/etc/fechadia}
LIMITE=50
AQUI=$(cd "$(dirname "$0")" && pwd)
vigia=$(sed -n 's/^FECHADIA_VIGIA_RECUSAS=//p' "$CONFIGURACAO/fechadia.env")

if [ -z "$vigia" ]; then
    echo "Falta FECHADIA_VIGIA_RECUSAS na configuração." >&2
    exit 1
fi

log=$(mktemp)
trap 'rm -f "$log"' EXIT
if ! sh "$AQUI/compose.sh" logs --no-color --no-log-prefix --since 5m app > "$log"; then
    echo "O log da aplicação não pôde ser lido; nenhum sinal enviado." >&2
    exit 1
fi

# O valor de cada campo do evento nunca tem espaço, então o nome vem entre espaços ou no fim da linha,
# e um caminho que contenha o texto de um evento não conta.
recusas=$(grep -cE \
    ' evento=(login\.recusado|senha\.troca_recusada|email\.troca_recusada|chave_pix\.troca_recusada|token\.recusado|acesso\.recusado|webhook\.recusado)( |$)' \
    "$log")
echo "$recusas recusas nos últimos 5 minutos, com limite de $LIMITE."

if [ "$recusas" -gt "$LIMITE" ]; then
    resultado=1
    sinal="$vigia/fail"
else
    resultado=0
    sinal=$vigia
fi
curl --fail --silent --show-error --max-time 10 --retry 3 --output /dev/null "$sinal" \
    || echo "O vigia não recebeu o sinal das recusas." >&2
exit "$resultado"
