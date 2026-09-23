/**
 * Casos de uso dos relatórios.
 *
 * <p>O Modulith expõe apenas o pacote-base de cada módulo; todo subpacote é interno até que se diga
 * o contrário, e para este pacote não se diz: nenhum outro módulo chama um relatório. Quem chama
 * daqui é a camada web deste mesmo módulo, e ninguém depende de relatórios.
 *
 * <p>Este módulo só lê. Ele pergunta ao banco, pelos mapeamentos somente leitura de
 * {@code relatorios.internal}, e responde com um record; não move agregado de ninguém, não publica
 * evento e não ouve nenhum. Os três relatórios, faturamento, mais vendidos e fluxo de caixa,
 * recebem o mesmo período de dias do balcão e o tratam do mesmo jeito, em {@code Periodo}. Os
 * filtros além do período (RF24) são do faturamento, por forma de pagamento e por operador, e do
 * ranking, por operador; o fluxo de caixa não os tem, porque é só dinheiro em espécie e o
 * operador da gaveta é assunto do histórico do caixa.
 */
package br.com.caixasimples.relatorios.application;
