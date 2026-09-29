# Imagem do job da cópia de hora em hora e da restauração: o cliente do PostgreSQL na mesma versão
# maior do servidor, o age para cifrar e o rclone para enviar e buscar.
FROM postgres:17-alpine

RUN apk add --no-cache age rclone jq

# O destino do rclone é configurado só por variáveis (RCLONE_CONFIG_<NOME>_*). Vazio, este caminho
# diz ao rclone que não há arquivo de configuração, e o log do job não abre com o aviso de que ele
# falta.
ENV RCLONE_CONFIG=""

COPY copia/copia.sh copia/restaurar.sh copia/reaplicar-remocoes.sh /copia/
COPY copia/reaplicar-cliente.sql copia/reaplicar-usuario.sql copia/reaplicar-nome-usuario.sql /copia/
COPY copia/conferencia.sql /copia/
COPY exclusao/apagar-conta.sql exclusao/encerrar-conta.sh /exclusao/

# Pontos de montagem para os dois destinos locais do ensaio. Na hospedagem o destino é externo.
# Um volume novo herda o dono da pasta da imagem, que precisa aceitar a escrita do usuário do job.
RUN mkdir /copias /remocoes && chown postgres:postgres /copias /remocoes

# Não precisa de root, e o usuário postgres já existe na imagem.
USER postgres
WORKDIR /tmp

# Sem o ponto de entrada da imagem, que inicializaria um servidor. Os scripts rodam pelo sh porque
# o bit de execução não sobrevive a todo checkout.
ENTRYPOINT []
CMD ["sh", "/copia/copia.sh"]
