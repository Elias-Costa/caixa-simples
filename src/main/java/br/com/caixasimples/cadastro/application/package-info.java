/**
 * Casos de uso do cadastro, e a API que os outros módulos enxergam.
 *
 * <p>O Modulith expõe apenas o pacote-base de cada módulo; todo subpacote é interno até que se diga
 * o contrário. A anotação abaixo diz o contrário para este pacote, e só para ele: quem precisa
 * perguntar algo ao cadastro, como o módulo de vendas ao copiar o preço de um produto, chama um
 * caso de uso daqui. {@code domain} e {@code internal} continuam ocultos, então a raiz do
 * agregado, com seus mutadores, e as entidades JPA não vazam para fora do módulo.
 *
 * <p>Vale o mesmo critério de fronteira de sempre: chamada direta faz pergunta, efeito colateral
 * entre módulos é evento.
 */
@NamedInterface("application")
package br.com.caixasimples.cadastro.application;

import org.springframework.modulith.NamedInterface;
