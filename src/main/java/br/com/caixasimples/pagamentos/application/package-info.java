/**
 * Casos de uso de pagamento, e a API que os outros módulos enxergam.
 *
 * <p>O Modulith expõe apenas o pacote-base de cada módulo; todo subpacote é interno até que se diga
 * o contrário. A anotação abaixo diz o contrário para este pacote: quem precisa pagar uma parcela,
 * como o módulo de vendas ao registrar um pagamento, chama {@code PaymentService} daqui, e é ele
 * que encontra a estratégia da forma pedida. {@code internal} continua oculto, então as
 * implementações de cada forma não vazam para fora do módulo.
 *
 * <p>Vale o mesmo critério de fronteira de sempre: chamada direta faz pergunta, efeito colateral
 * entre módulos é evento. Pagar uma parcela é pergunta, porque {@code PaymentService} não abre
 * transação nem grava nada: ele responde o que fica registrado, e quem grava é o agregado Venda,
 * dono da parcela.
 */
@NamedInterface("application")
package br.com.caixasimples.pagamentos.application;

import org.springframework.modulith.NamedInterface;
