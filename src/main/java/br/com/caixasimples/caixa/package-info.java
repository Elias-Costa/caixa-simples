/**
 * Bounded Context de caixa: agregado {@code SessaoCaixa}, raiz que tem {@code MovimentoCaixa} como
 * membro, cobrindo abertura, sangria, suprimento e fechamento com conferência.
 *
 * <p>Vocabulário do domínio: <em>sangria</em> é retirada de dinheiro, <em>suprimento</em> é reforço
 * de troco, e <em>caixa</em> é a sessão operacional entre abertura e fechamento.
 */
@ApplicationModule(displayName = "Caixa")
package br.com.caixasimples.caixa;

import org.springframework.modulith.ApplicationModule;
