/**
 * Bounded Context de pagamentos: Strategy por forma de pagamento, entre dinheiro, Pix e cartão, e
 * Adapter por provedor de Pix.
 *
 * <p>Nenhuma regra de negócio fora deste módulo conhece o nome de um provedor (RF27). Os adapters
 * ficam em {@code internal}, atrás de uma interface de domínio, que é o que permite trocar de
 * provedor sem tocar na regra.
 */
@ApplicationModule(displayName = "Pagamentos")
package br.com.caixasimples.pagamentos;

import org.springframework.modulith.ApplicationModule;
