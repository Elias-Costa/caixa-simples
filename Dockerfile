# Imagem do jar único: a API e o aplicativo (PWA) na mesma origem.
#
# Os testes Java não rodam aqui, porque sobem um PostgreSQL em contêiner e o build da imagem não
# tem Docker dentro; quem os roda é o CI, antes de o commit virar deploy. Os testes do front-end
# rodam, porque fazem parte do empacotamento.

# As bases ficam presas por digest: a imagem conferida pela verificação de avisos é a mesma que se
# publica, e reconstruir o mesmo commit dá a mesma base. A troca vem do Dependabot, uma vez por mês.
#
# Imagem com glibc de propósito: o empacotamento baixa o Node oficial, que não roda sobre musl.
FROM eclipse-temurin:21-jdk-noble@sha256:b468c3fc688b14450571494f588bd939378e7fd542ed5a73f8efc13f17872a87 AS build
WORKDIR /build

# O wrapper confere o checksum do zip do Maven, o mesmo na máquina e no CI. Sem o unzip, ele baixaria
# o pacote .tar.gz, que tem outro checksum, e recusaria a distribuição. Fica só neste estágio.
RUN apt-get update \
    && apt-get install -y --no-install-recommends unzip \
    && rm -rf /var/lib/apt/lists/*

# As dependências do Maven ficam numa camada própria, que o cache reaproveita enquanto o pom.xml
# não muda.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN sh mvnw -B -ntp dependency:go-offline

COPY frontend/ frontend/
COPY src/main/ src/main/
RUN sh mvnw -B -ntp package -Dmaven.test.skip=true

# Separa o jar em camadas, das que mudam menos para as que mudam mais. Uma versão nova que só
# altera o código da aplicação reenvia só a última camada, e o layout extraído sobe mais rápido
# que o jar aninhado.
FROM eclipse-temurin:21-jre-alpine@sha256:51ab5e3302e7141ce665ca3ea85e8b5cd648eafbc3c0c90dd79d6537684e4555 AS camadas
WORKDIR /camadas
COPY --from=build /build/target/caixa-simples-*.jar aplicacao.jar
RUN java -Djarmode=tools -jar aplicacao.jar extract --layers --destination extraido

FROM eclipse-temurin:21-jre-alpine@sha256:51ab5e3302e7141ce665ca3ea85e8b5cd648eafbc3c0c90dd79d6537684e4555
# Liga a imagem publicada a este repositório no registro, e é por ele que o deploy acha as versões
# antigas para apagar.
LABEL org.opencontainers.image.source=https://github.com/Elias-Costa/fechadia
# Uid e gid fixos, para o servidor dar a este usuário, e só a ele, a leitura dos certificados Pix
# montados no contêiner.
RUN addgroup -S -g 10001 caixa && adduser -S -u 10001 -G caixa caixa
WORKDIR /aplicacao
COPY --from=camadas /camadas/extraido/dependencies/ ./
COPY --from=camadas /camadas/extraido/spring-boot-loader/ ./
COPY --from=camadas /camadas/extraido/snapshot-dependencies/ ./
COPY --from=camadas /camadas/extraido/application/ ./
USER caixa

# A porta de verdade vem de FECHADIA_PORT; esta é o padrão da aplicação.
EXPOSE 8080

# Medido sob carga com meia CPU e com limites de 512 MB e de 1 GB: depois do GC ficam cerca de
# 100 MB vivos no heap, que a JVM expande até uns 220 MB, e ela ocupa perto de 245 MB fora dele
# (metaspace, código compilado, símbolos). Com o heap limitado a 45% da memória do contêiner, a
# soma fica abaixo do limite mesmo com o heap no máximo, inclusive em 512 MB, onde 60% passaria.
# A fração, e não um valor fixo, acompanha uma troca de plano sem novo build.
# O coletor serial é o que a JVM escolhe quando enxerga uma CPU só, e fica explícito porque a
# medição foi feita com ele: outro coletor gastaria mais memória fora do heap.
# Sem memória, o processo termina em vez de seguir degradado, e a hospedagem sobe outro.
ENV JAVA_TOOL_OPTIONS="-XX:+UseSerialGC -XX:MaxRAMPercentage=45 -XX:+ExitOnOutOfMemoryError"

# Comando, e não ponto de entrada, para que uma execução avulsa da mesma imagem, como a que cria uma
# Conta, troque o comando inteiro. O ponto de entrada da imagem base só trataria certificados que
# esta imagem não usa.
ENTRYPOINT []
CMD ["java", "-jar", "aplicacao.jar"]
