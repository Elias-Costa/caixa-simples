# O PostgreSQL 17 da VPS: a imagem oficial, presa por digest, começando já como o usuário postgres.
#
# O ponto de entrada da imagem oficial só chama o gosu quando começa como root, para trocar para o
# usuário postgres. Começando como ele, o gosu nunca roda, e apagado, ele não traz para cá os avisos
# de segurança da versão do Go com que foi compilado. A pasta dos dados já é do usuário postgres na
# imagem, e o volume novo herda esse dono.
#
# Construída na própria VPS pelo compose, e não publicada: muda só quando este arquivo muda, e cada
# mudança recria o banco, por isso vai ao ar só na janela de manutenção.
FROM postgres:17-alpine@sha256:b0f9560a2de083e2cc7382e75f808c7381a32852a7ec49117deedb300e552b24

RUN rm -f /usr/local/bin/gosu

USER postgres
