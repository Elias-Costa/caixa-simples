#!/bin/sh
# Verificação dos avisos de segurança das dependências, a mesma no CI e na máquina. Constrói as duas
# imagens deste checkout e confere, pelo Trivy, os pacotes delas, as bibliotecas Java dentro da
# imagem da aplicação, as imagens do proxy e do banco que rodam na VPS e o package-lock do front-end,
# inclusive as dependências de build, que geram o pacote e o service worker servidos.
#
# Barra a publicação o aviso alto ou crítico que já tem versão corrigida. Os demais, de severidade
# menor ou ainda sem correção, aparecem no relatório de cada alvo sem barrar. A exceção fundamentada
# a um aviso entra em excecoes.yaml, ao lado deste script, com data de revisão.
#
# Uso, com o Docker rodando: sh operacao/dependencias/verificar.sh
set -eu

# A imagem oficial da ferramenta, presa pelo digest, que é o mesmo no Docker Hub, no GHCR e no ECR
# Public. Só a tag não bastaria: em março de 2026, as tags da action da ferramenta e uma versão dela
# foram trocadas por código que roubava segredos do CI, e um digest não pode ser trocado.
TRIVY=ghcr.io/aquasecurity/trivy:0.74.0@sha256:62b1e65e8869bc4b4c6aa4fa2b21595256c7c2f6018a9d9ad61caf87187c1969

# No Git Bash do Windows, sem esta variável, os caminhos de dentro do contêiner, como /repo, viram
# caminhos do Windows antes de chegar ao Docker. No Linux ela não tem efeito.
export MSYS_NO_PATHCONV=1

cd "$(dirname "$0")/../.."
# O Docker no Windows precisa da pasta no formato C:/...; o pwd -W só existe no Git Bash.
repositorio=$(pwd -W 2>/dev/null || pwd)

# Com --pull, as imagens base vêm sempre do registro, como no build da hospedagem: com uma base
# antiga guardada na máquina, a verificação examinaria uma imagem diferente da publicada.
docker build --pull --tag caixa-simples:verificacao .
docker build --pull --tag caixa-simples-copia:verificacao --file operacao/copia/Dockerfile operacao

# As imagens de terceiros que rodam na VPS: o proxy, lido do compose dela, onde fica preso por
# digest, e o banco, construído do mesmo Dockerfile que a VPS constrói, com a base presa por digest.
# É exatamente o que vai ao ar, e trocar o digest lá troca o que se confere aqui.
proxy=$(grep -oE 'caddy:[^ ]+@sha256:[0-9a-f]{64}' operacao/vps/compose.yaml)
docker pull --quiet "$proxy"
docker build --pull --tag caixa-simples-banco:verificacao operacao/vps/banco

# A ferramenta lê as imagens pelo Docker da máquina, nunca de um registro, e o package-lock e as
# exceções pela pasta do repositório, montada só para leitura. As bases de avisos ficam num volume,
# para uma nova execução na mesma máquina não as baixar de novo.
trivy() {
  docker run --rm \
    --volume /var/run/docker.sock:/var/run/docker.sock \
    --volume "$repositorio:/repo:ro" \
    --volume caixa-simples-trivy:/cache \
    "$TRIVY" "$@" --cache-dir /cache --scanners vuln --no-progress --skip-version-check
}

barrados=""

# Primeiro o relatório completo, que não barra; depois só o que barra. O código 3 distingue o aviso
# encontrado da falha da própria ferramenta, que sai com outro código e interrompe a verificação.
verificar() {
  alvo=$1
  shift
  echo "==> $alvo: relatório completo"
  trivy "$@" --exit-code 0
  echo "==> $alvo: avisos que barram a publicação"
  resultado=0
  trivy "$@" --severity HIGH,CRITICAL --ignore-unfixed \
    --ignorefile /repo/operacao/dependencias/excecoes.yaml --exit-code 3 || resultado=$?
  case $resultado in
    0) ;;
    3)
      if [ -z "$barrados" ]; then
        barrados=$alvo
      else
        barrados="$barrados; $alvo"
      fi
      ;;
    *)
      echo "A ferramenta falhou ao verificar $alvo." >&2
      exit "$resultado"
      ;;
  esac
}

verificar "imagem da aplicação" image --image-src docker caixa-simples:verificacao
verificar "imagem da cópia" image --image-src docker caixa-simples-copia:verificacao
verificar "imagem do proxy" image --image-src docker "$proxy"
verificar "imagem do banco" image --image-src docker caixa-simples-banco:verificacao
verificar "package-lock do front-end" fs --include-dev-deps /repo/frontend/package-lock.json

if [ -n "$barrados" ]; then
  echo "Aviso alto ou crítico com correção, sem exceção válida, em: $barrados." >&2
  exit 1
fi
echo "Nenhum aviso alto ou crítico com correção."
