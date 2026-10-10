#!/bin/sh
# A cópia de hora em hora, pelo agendador da máquina: roda a imagem da cópia da versão no ar e avisa
# o vigia do resultado. O aviso é um GET sem corpo, só com o endereço do check: nem o log da cópia
# nem nada das Contas sai daqui.
#
# Uso: sh copia-agendada.sh, pelo timer caixa-simples-copia.
set -u

AQUI=$(cd "$(dirname "$0")" && pwd)
CONFIGURACAO=${CAIXA_SIMPLES_CONFIGURACAO:-/etc/caixa-simples}
vigia=$(sed -n 's/^CAIXA_SIMPLES_VIGIA_COPIA=//p' "$CONFIGURACAO/caixa-simples.env")

if sh "$AQUI/compose.sh" --profile copia run --rm -T copia; then
    resultado=0
    sinal=$vigia
else
    resultado=1
    sinal="$vigia/fail"
fi

# Sem o endereço, a cópia ainda sai, mas uma hora sem cópia passaria sem aviso.
if [ -z "$vigia" ]; then
    echo "Falta CAIXA_SIMPLES_VIGIA_COPIA na configuração: o vigia não soube desta cópia." >&2
    exit 1
fi
curl --fail --silent --show-error --max-time 10 --retry 5 --output /dev/null "$sinal" \
    || echo "O vigia não recebeu o sinal desta cópia." >&2
exit "$resultado"
