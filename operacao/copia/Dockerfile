# Imagem do job da cópia de hora em hora e da restauração: o cliente do PostgreSQL na mesma versão
# maior do servidor, o age para cifrar e o rclone para enviar e buscar.
FROM postgres:17-alpine

RUN apk add --no-cache age rclone

# O destino do rclone é configurado só por variáveis (RCLONE_CONFIG_<NOME>_*). Vazio, este caminho
# diz ao rclone que não há arquivo de configuração, e o log do job não abre com o aviso de que ele
# falta.
ENV RCLONE_CONFIG=""

COPY copia.sh restaurar.sh conferencia.sql /copia/

# Ponto de montagem para quando o destino é uma pasta, como no ensaio local; na hospedagem o
# destino é o armazenamento externo, e ela fica vazia. É do usuário do job porque um volume novo
# herda o dono da pasta da imagem.
RUN mkdir /copias && chown postgres:postgres /copias

# Não precisa de root, e o usuário postgres já existe na imagem.
USER postgres
WORKDIR /tmp

# Sem o ponto de entrada da imagem, que inicializaria um servidor. Os scripts rodam pelo sh porque
# o bit de execução não sobrevive a todo checkout.
ENTRYPOINT []
CMD ["sh", "/copia/copia.sh"]
