/**
 * Bounded Context de caixa: agregado {@code SessaoCaixa} (raiz, com {@code MovimentoCaixa} como
 * membro) — abertura, sangria, suprimento e fechamento com conferencia.
 *
 * <p>Vocabulario (linguagem ubiqua): <em>sangria</em> e retirada de dinheiro, <em>suprimento</em> e
 * reforco de troco, e <em>caixa</em> e a sessao operacional entre abertura e fechamento.
 */
@ApplicationModule(displayName = "Caixa")
package br.com.caixasimples.caixa;

import org.springframework.modulith.ApplicationModule;
