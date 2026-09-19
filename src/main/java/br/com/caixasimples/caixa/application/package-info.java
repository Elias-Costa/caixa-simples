/**
 * Casos de uso do caixa.
 *
 * <p>O Modulith expõe apenas o pacote-base de cada módulo; todo subpacote é interno até que se diga
 * o contrário, e para este pacote não se diz: nenhum outro módulo chama um caso de uso daqui. A
 * única pergunta que vem de fora, se uma sessão está aberta, chega pela interface que o módulo de
 * vendas declara e que {@code caixa.internal} implementa. O sentido é esse, e não o inverso,
 * porque o caixa ouve o evento de venda concluída e por isso já depende de vendas; a verificação
 * de fronteiras não aceita os dois sentidos ao mesmo tempo.
 *
 * <p>Vale o critério de fronteira de sempre: chamada direta faz pergunta, efeito colateral entre
 * módulos é evento. O dinheiro de uma venda concluída entra no caixa por evento, ouvido em
 * {@code caixa.internal}, nunca por chamada a este pacote.
 */
package br.com.caixasimples.caixa.application;
