/**
 * Casos de uso dos relatórios.
 *
 * <p>O Modulith expõe apenas o pacote-base de cada módulo; todo subpacote é interno até que se diga
 * o contrário, e para este pacote não se diz: nenhum outro módulo chama um relatório. Quem chama
 * daqui é a camada web deste mesmo módulo, quando nascer, e ninguém depende de relatórios.
 *
 * <p>Este módulo só lê. Ele pergunta ao banco, pelos mapeamentos somente leitura de
 * {@code relatorios.internal}, e responde com um record; não move agregado de ninguém, não publica
 * evento e não ouve nenhum.
 */
package br.com.caixasimples.relatorios.application;
