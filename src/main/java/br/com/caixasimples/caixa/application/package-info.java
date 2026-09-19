/**
 * Casos de uso do caixa, e a API que os outros módulos enxergam.
 *
 * <p>O Modulith expõe apenas o pacote-base de cada módulo; todo subpacote é interno até que se diga
 * o contrário. A anotação abaixo diz o contrário para este pacote, e só para ele: quem precisa
 * perguntar algo ao caixa, como o módulo de vendas ao conferir se a sessão está aberta antes de
 * iniciar uma venda, chama um caso de uso daqui. {@code domain} e {@code internal} continuam
 * ocultos, então a raiz do agregado, com seus mutadores, e as entidades JPA não vazam para fora
 * do módulo.
 *
 * <p>Vale o mesmo critério de fronteira de sempre: chamada direta faz pergunta, efeito colateral
 * entre módulos é evento. O dinheiro de uma venda concluída entra no caixa por evento, nunca por
 * chamada a este pacote.
 */
@NamedInterface("application")
package br.com.caixasimples.caixa.application;

import org.springframework.modulith.NamedInterface;
