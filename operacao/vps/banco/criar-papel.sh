#!/bin/sh
# Cria o papel da aplicação e o banco dela, uma vez, no primeiro initdb do volume, como superusuário
# e pelo socket local do servidor temporário da imagem, que não escuta na rede.
#
# O papel é dono do banco e não tem nenhum atributo de superusuário. Aplicação, migrations, cópia,
# restauração e encerramento de Conta conectam com ele; uma consulta injetada não alcançaria o que
# só o superusuário faz: executar comando na máquina do banco por COPY TO PROGRAM, ler arquivo do
# servidor ou criar papel. O superusuário fica para este script e para a manutenção de dentro do
# contêiner. No PostgreSQL 17 o schema public pertence ao dono do banco, então o papel cria as
# tabelas sem nenhum GRANT.
#
# A senha vem do ambiente pelo \getenv do psql: não passa pela linha de comando de nenhum processo,
# e :'senha' a escreve como literal do SQL, com qualquer caractere.
set -eu

: "${FECHADIA_DB_PASSWORD:?informe a senha do papel da aplicação}"

psql --no-psqlrc --set=ON_ERROR_STOP=1 --username="$POSTGRES_USER" --dbname=postgres <<'SQL'
\getenv senha FECHADIA_DB_PASSWORD
CREATE ROLE fechadia LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS
    PASSWORD :'senha';
CREATE DATABASE fechadia OWNER fechadia;
SQL
