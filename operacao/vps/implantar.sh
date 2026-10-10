#!/bin/sh
# O deploy de um commit, pedido pelo CI, de madrugada ou pelo botão de urgência, pela chave SSH que
# só pode rodar este script; o SHA é tudo o que o CI manda. O preparo o instala como root fora do
# repositório, em /usr/local/sbin/caixa-simples-implantar, para que um commit não troque as regras
# do próprio deploy; mudou este arquivo, o preparo copia de novo.
#
# Recusa, e o CI fica vermelho:
# - o que não for um SHA completo;
# - o commit que não está no main: o GitHub entrega pelo SHA também commits de forks, e quem
#   tivesse a chave poderia pedir um deles;
# - fora da janela de manutenção, das 3h às 8h UTC, o commit que muda migration, o compose desta
#   pasta ou a imagem do banco: a migration altera o schema com a aplicação parada, e os outros dois
#   podem recriar o banco ou o proxy. O botão não passa por cima disso; a execução da madrugada o
#   leva na noite seguinte.
#
# A aplicação antiga para antes de a nova subir, porque as duas não cabem juntas na memória; o proxy
# segura as requisições enquanto isso, e por isso o deploy de rotina é de madrugada: uma requisição
# já em andamento quando a antiga fecha a conexão volta com erro. A versão no ar só é gravada depois
# de a nova ficar saudável.
set -eu

# Um cancelamento no CI derruba a conexão SSH; o deploy segue até o fim em vez de parar no meio.
trap '' HUP

REPOSITORIO=${CAIXA_SIMPLES_REPOSITORIO:-/opt/caixa-simples/repositorio}
ESTADO=${CAIXA_SIMPLES_ESTADO:-/var/lib/caixa-simples}
VERSAO="$ESTADO/versao.env"
FONTE=https://github.com/Elias-Costa/caixa-simples

# Pela chave do CI, o SHA chega como o comando que o cliente pediu; à mão, como argumento.
sha=${SSH_ORIGINAL_COMMAND:-${1:-}}

recusar() {
    echo "Deploy recusado: $1" >&2
    exit 1
}

case $sha in
    *[!0-9a-f]* | '') recusar "informe o SHA completo do commit, em minúsculas." ;;
esac
[ "${#sha}" -eq 40 ] || recusar "informe o SHA completo do commit, com 40 caracteres."

# Dois deploys ao mesmo tempo trocariam a versão um do outro: o segundo espera o primeiro.
mkdir -p "$ESTADO"
exec 9>"$ESTADO/implantacao.trava"
flock --wait 600 9 || recusar "outro deploy não terminou em 10 minutos."

git -C "$REPOSITORIO" fetch --quiet origin main
git -C "$REPOSITORIO" merge-base --is-ancestor "$sha" origin/main 2>/dev/null \
    || recusar "o commit $sha não está no main."

atual=""
if [ -f "$VERSAO" ]; then
    atual=$(sed -n 's/^CAIXA_SIMPLES_VERSAO=//p' "$VERSAO")
fi
# A execução da madrugada pede todo dia o último commit aprovado, que quase sempre já está no ar.
if [ "$sha" = "$atual" ]; then
    echo "Já no ar: $sha."
    exit 0
fi
# O primeiro deploy, num banco vazio, não tem quem atrapalhar e vai a qualquer hora.
if [ -n "$atual" ] && ! git -C "$REPOSITORIO" diff --quiet "$atual" "$sha" -- \
        src/main/resources/db/migration operacao/vps/compose.yaml operacao/vps/banco; then
    case $(date -u +%H) in
        03 | 04 | 05 | 06 | 07) ;;
        *) recusar "o commit muda migration, o compose ou o banco e só vai na janela, das 3h às 8h UTC." ;;
    esac
fi

git -C "$REPOSITORIO" checkout --quiet --detach "$sha"
compose="$REPOSITORIO/operacao/vps/compose.sh"

# A versão nova vai pelo ambiente, que vale mais que o arquivo, até ela provar que sobe.
CAIXA_SIMPLES_VERSAO=$sha
export CAIXA_SIMPLES_VERSAO
echo "Baixando as imagens de $sha."
# A imagem do banco não vem do registro: é construída aqui, pelo up logo abaixo.
sh "$compose" --profile copia pull --quiet --ignore-buildable
echo "Trocando a aplicação."
sh "$compose" up --detach --wait --wait-timeout 300 \
    || recusar "a versão $sha não ficou saudável em 5 minutos; a anterior era ${atual:-nenhuma}."
# Aplica um Caddyfile alterado sem derrubar conexões; sem mudança, não faz nada.
sh "$compose" exec -T proxy caddy reload --config /etc/caddy/Caddyfile \
    || recusar "o proxy recusou o Caddyfile de $sha e continua com o anterior."

printf 'CAIXA_SIMPLES_VERSAO=%s\n' "$sha" > "$VERSAO.nova"
mv "$VERSAO.nova" "$VERSAO"

# As imagens deste repositório que não são da versão no ar só ocupariam o disco: a volta a uma
# versão anterior baixa a dela de novo.
docker image ls --filter "label=org.opencontainers.image.source=$FONTE" \
        --format '{{.Repository}}:{{.Tag}}' \
    | grep -v ":$sha\$" \
    | xargs -r docker image rm >/dev/null 2>&1 || true

echo "No ar: $sha."
