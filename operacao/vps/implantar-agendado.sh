#!/bin/sh
# O deploy da madrugada, pelo timer ecrx-caixa-implantar, às 3h UTC: pergunta ao GitHub qual é o
# último commit do main cuja execução de push do CI passou inteira e o entrega ao script de deploy,
# que confere o pedido, só aceita na janela o commit que precisa dela e não faz nada se o commit já
# está no ar. A agenda do próprio GitHub chega a disparar horas depois da hora marcada, fora da janela;
# o relógio da máquina não atrasa. O repositório é público, e a pergunta não precisa de token.
#
# O preparo o instala como root fora do repositório, ao lado do script de deploy, para que um commit
# não troque as regras do próprio deploy; mudou este arquivo, o preparo copia de novo. O resultado vai
# ao vigia num GET sem corpo, só com o endereço do check; o log fica no journald da máquina.
#
# Uso: ecrx-caixa-implantar-agendado, pelo timer ecrx-caixa-implantar.
set -u

CONFIGURACAO=${ECRX_CAIXA_CONFIGURACAO:-/etc/ecrx-caixa}
IMPLANTAR=/usr/local/sbin/ecrx-caixa-implantar
# A mesma pergunta que o botão do CI faz antes do deploy.
APROVADOS='https://api.github.com/repos/Elias-Costa/ecrx-caixa/actions/workflows/ci.yml/runs?branch=main&event=push&status=success&per_page=1'

vigia=$(sed -n 's/^ECRX_CAIXA_VIGIA_DEPLOY=//p' "$CONFIGURACAO/ecrx-caixa.env")

ultimo_aprovado() {
    curl --fail --silent --show-error --max-time 30 --retry 3 \
            --header 'Accept: application/vnd.github+json' "$APROVADOS" \
        | jq -r '.workflow_runs[0].head_sha | strings'
}

# Sem resposta da API, com uma resposta que não é JSON ou sem nenhuma execução aprovada, o jq não
# devolve nada; o código de saída dele não serve para isso, porque com a entrada vazia ele sai com
# sucesso. O formato do SHA quem confere é o script de deploy.
sha=$(ultimo_aprovado)
if [ -n "$sha" ]; then
    echo "Último commit aprovado: $sha."
    if "$IMPLANTAR" "$sha"; then
        resultado=0
    else
        resultado=1
    fi
else
    echo "O GitHub não disse qual é o último commit aprovado; nada mudou na máquina." >&2
    resultado=1
fi

if [ "$resultado" -eq 0 ]; then
    sinal=$vigia
else
    sinal="$vigia/fail"
fi

# Sem o endereço, o deploy ainda roda, mas uma recusa passaria sem aviso.
if [ -z "$vigia" ]; then
    echo "Falta ECRX_CAIXA_VIGIA_DEPLOY na configuração: o vigia não soube deste deploy." >&2
    exit 1
fi
curl --fail --silent --show-error --max-time 10 --retry 5 --output /dev/null "$sinal" \
    || echo "O vigia não recebeu o sinal deste deploy." >&2
exit "$resultado"
