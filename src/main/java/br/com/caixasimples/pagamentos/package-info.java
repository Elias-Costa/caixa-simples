/**
 * Bounded Context de pagamentos: Strategy por forma de pagamento (dinheiro, Pix, cartao) e Adapter
 * por PSP de Pix.
 *
 * <p>Nenhuma regra de negocio fora deste modulo conhece o nome de um PSP (RF27) — os adapters
 * ficam em {@code internal}, atras da interface {@code PixGateway}. Convencoes na skill
 * {@code padroes-pagamento}.
 */
@ApplicationModule(displayName = "Pagamentos")
package br.com.caixasimples.pagamentos;

import org.springframework.modulith.ApplicationModule;
