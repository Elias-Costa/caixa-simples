#!/bin/sh
# Preparo de uma VPS nova com AlmaLinux 9, como root, uma vez: atualização automática de segurança,
# firewall, SSH só por chave, swap, logs com prazo, Docker, o usuário do deploy, o repositório e as
# tarefas agendadas. Os segredos não passam por aqui: o arquivo de configuração sai vazio do
# criar-configuracao.sh e é preenchido à mão depois. Rodar de novo refaz cada passo sem duplicar nada.
#
# Uso, depois de criar o usuário administrador com a chave SSH dele e de copiar para a máquina a
# chave pública do CI:
#   sh preparar.sh <usuário administrador> <arquivo da chave pública do CI>
set -eu

ADMIN=${1:?informe o usuário administrador, que entra por SSH com chave}
CHAVE_DO_CI=${2:?informe o arquivo com a chave pública do CI}
FONTE=https://github.com/Elias-Costa/caixa-simples.git
REPOSITORIO=/opt/caixa-simples/repositorio
ESTADO=/var/lib/caixa-simples
CONFIGURACAO=/etc/caixa-simples
IMPLANTAR=/usr/local/sbin/caixa-simples-implantar
# O usuário da imagem da aplicação, fixo no Dockerfile: é ele que lê os certificados Pix montados.
UID_DA_APLICACAO=10001

passo() {
    echo "==> $1"
}

if [ "$(id -u)" -ne 0 ]; then
    echo "Rode como root." >&2
    exit 1
fi
if ! grep -q '^ID="almalinux"' /etc/os-release || ! grep -q '^VERSION_ID="9' /etc/os-release; then
    echo "Este preparo é para o AlmaLinux 9." >&2
    exit 1
fi

passo "Administrador com chave, antes de o SSH recusar senha e root"
if ! id "$ADMIN" >/dev/null 2>&1; then
    echo "Crie o usuário $ADMIN, com senha e com a sua chave SSH, antes do preparo." >&2
    exit 1
fi
casa=$(getent passwd "$ADMIN" | cut -d: -f6)
if [ ! -s "$casa/.ssh/authorized_keys" ]; then
    echo "Sem chave em $casa/.ssh/authorized_keys: com o SSH só por chave, você ficaria fora." >&2
    exit 1
fi
usermod -aG wheel "$ADMIN"
ssh-keygen -l -f "$CHAVE_DO_CI" >/dev/null

passo "Fuso UTC, o das tarefas agendadas e da janela de manutenção"
timedatectl set-timezone UTC

passo "Sistema atualizado e pacotes de base"
dnf -y upgrade
dnf -y install dnf-automatic dnf-plugins-core firewalld git curl openssl util-linux sudo

passo "Atualização automática só de segurança, aplicada na janela, com reinício quando pedido"
sed -i \
    -e 's/^upgrade_type *=.*/upgrade_type = security/' \
    -e 's/^apply_updates *=.*/apply_updates = yes/' \
    -e 's/^reboot *=.*/reboot = when-needed/' \
    /etc/dnf/automatic.conf
mkdir -p /etc/systemd/system/dnf-automatic.timer.d

passo "Firewall: só SSH, HTTP e HTTPS"
systemctl enable --now firewalld
firewall-cmd --permanent --zone=public --add-service=ssh --add-service=http --add-service=https
firewall-cmd --permanent --zone=public --remove-service=cockpit >/dev/null 2>&1 || true
firewall-cmd --reload

passo "SSH só por chave e sem root"
# O sshd fica com o primeiro valor que lê, e os arquivos desta pasta são lidos em ordem alfabética:
# o prefixo 00 vem antes de qualquer arquivo de fábrica que libere senha ou root.
cat > /etc/ssh/sshd_config.d/00-caixa-simples.conf <<'FIM'
PermitRootLogin no
PasswordAuthentication no
KbdInteractiveAuthentication no
FIM
sshd -t
systemctl reload sshd

passo "Swap de 1 GB, usado só no aperto"
if [ -z "$(swapon --show=NAME --noheadings)" ]; then
    dd if=/dev/zero of=/swapfile bs=1M count=1024 status=none
    chmod 600 /swapfile
    mkswap /swapfile >/dev/null
    swapon /swapfile
    grep -q '^/swapfile ' /etc/fstab || echo '/swapfile none swap defaults 0 0' >> /etc/fstab
else
    echo "A máquina já tem swap, mantido:"
    swapon --show
fi
echo 'vm.swappiness = 10' > /etc/sysctl.d/90-caixa-simples.conf
sysctl --quiet --system

passo "Logs do sistema e dos contêineres por 7 dias, no journald"
# Os logs podem levar o endereço de quem acessa: um arquivo por dia, apagado depois de 7, com teto
# de espaço.
mkdir -p /etc/systemd/journald.conf.d
cat > /etc/systemd/journald.conf.d/caixa-simples.conf <<'FIM'
[Journal]
Storage=persistent
MaxRetentionSec=7day
MaxFileSec=1day
SystemMaxUse=1G
FIM
systemctl restart systemd-journald

passo "Docker, do repositório oficial, com os logs dos contêineres no journald"
# O podman da instalação de fábrica disputa os mesmos pacotes com o Docker.
dnf -y remove podman runc >/dev/null 2>&1 || true
dnf config-manager --add-repo https://download.docker.com/linux/rhel/docker-ce.repo
dnf -y install docker-ce docker-ce-cli containerd.io docker-compose-plugin
mkdir -p /etc/docker
cat > /etc/docker/daemon.json <<'FIM'
{
  "log-driver": "journald"
}
FIM
systemctl enable docker
systemctl restart docker

passo "Repositório público, de onde o deploy lê o compose do commit"
mkdir -p /opt/caixa-simples
if [ ! -d "$REPOSITORIO/.git" ]; then
    git clone --quiet "$FONTE" "$REPOSITORIO"
fi
install -d -m 700 "$ESTADO"

passo "Usuário do deploy, cuja chave só roda o script de deploy"
id implantacao >/dev/null 2>&1 || useradd --system --create-home --shell /bin/sh implantacao
install -m 700 -o root -g root "$REPOSITORIO/operacao/vps/implantar.sh" "$IMPLANTAR"
casa_implantacao=$(getent passwd implantacao | cut -d: -f6)
install -d -m 700 -o implantacao -g implantacao "$casa_implantacao/.ssh"
printf 'restrict,command="sudo %s" %s\n' "$IMPLANTAR" "$(cat "$CHAVE_DO_CI")" \
    > "$casa_implantacao/.ssh/authorized_keys"
chown implantacao:implantacao "$casa_implantacao/.ssh/authorized_keys"
chmod 600 "$casa_implantacao/.ssh/authorized_keys"
# O sudo apaga o ambiente, e é nele que o SHA pedido chega: só este script o recebe, e sem
# argumento nenhum, que é o que as aspas vazias exigem.
cat > /etc/sudoers.d/caixa-simples-implantacao <<FIM
Defaults!$IMPLANTAR env_keep += "SSH_ORIGINAL_COMMAND"
implantacao ALL=(root) NOPASSWD: $IMPLANTAR ""
FIM
chmod 440 /etc/sudoers.d/caixa-simples-implantacao
visudo -cf /etc/sudoers.d/caixa-simples-implantacao >/dev/null

passo "Configuração, gerada uma vez"
if [ ! -f "$CONFIGURACAO/caixa-simples.env" ]; then
    sh "$REPOSITORIO/operacao/vps/criar-configuracao.sh" "$CONFIGURACAO"
fi
chown "$UID_DA_APLICACAO:$UID_DA_APLICACAO" "$CONFIGURACAO/efi"
chmod 700 "$CONFIGURACAO/efi"

passo "Tarefas agendadas: a atualização ligada agora, a cópia e o vigia a partir do boot"
install -m 644 "$REPOSITORIO/operacao/vps/systemd/dnf-automatic.timer.d/janela.conf" \
    /etc/systemd/system/dnf-automatic.timer.d/janela.conf
install -m 644 "$REPOSITORIO"/operacao/vps/systemd/caixa-simples-*.service \
    "$REPOSITORIO"/operacao/vps/systemd/caixa-simples-*.timer /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now dnf-automatic.timer
# Parados até o primeiro deploy, que é quando há versão para copiar e aplicação para conferir.
systemctl enable caixa-simples-copia.timer caixa-simples-vigia.timer

echo "Pronto. Preencha $CONFIGURACAO/caixa-simples.env e siga a primeira publicação."
