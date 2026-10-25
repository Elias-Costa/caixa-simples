#!/bin/sh
# O sinal de vida da máquina, a cada 5 minutos: só vai se a aplicação responde pelo domínio, passando
# pelo proxy e com um certificado válido. Sem o sinal, o vigia avisa o mantenedor, e a máquina fora
# do ar também deixa de mandá-lo. O sinal é um GET sem corpo.
#
# Uso: sh vigia.sh, pelo timer fechadia-vigia. A porta só muda no ensaio, onde o proxy fica em
# outra porta da máquina.
set -u

CONFIGURACAO=${FECHADIA_CONFIGURACAO:-/etc/fechadia}
PORTA=${FECHADIA_VIGIA_PORTA:-443}
dominio=$(sed -n 's/^FECHADIA_DOMINIO=//p' "$CONFIGURACAO/fechadia.env")
vigia=$(sed -n 's/^FECHADIA_VIGIA_MAQUINA=//p' "$CONFIGURACAO/fechadia.env")

if [ -z "$dominio" ] || [ -z "$vigia" ]; then
    echo "Faltam FECHADIA_DOMINIO ou FECHADIA_VIGIA_MAQUINA na configuração." >&2
    exit 1
fi

# O nome do domínio aponta para esta própria máquina: a conferência passa pelo proxy e pelo
# certificado do domínio, sem depender de a rede de fora devolver o pedido para cá.
if curl --fail --silent --show-error --max-time 10 --output /dev/null \
        --resolve "$dominio:$PORTA:127.0.0.1" "https://$dominio:$PORTA/actuator/health/liveness"; then
    resultado=0
    sinal=$vigia
else
    resultado=1
    sinal="$vigia/fail"
fi
curl --fail --silent --show-error --max-time 10 --retry 3 --output /dev/null "$sinal" \
    || echo "O vigia não recebeu o sinal da máquina." >&2
exit "$resultado"
