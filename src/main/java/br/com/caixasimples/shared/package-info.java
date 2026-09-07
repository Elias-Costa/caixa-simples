/**
 * Tipos genuinamente transversais: {@code ContaId}, {@code Money}, excecoes comuns e o contexto do
 * tenant. Modulo <strong>aberto</strong> — os outros modulos podem acessar seus subpacotes.
 *
 * <p>Nao e deposito de utilitario: so entra aqui o que mais de um Bounded Context precisa de
 * verdade. Regra em {@code .claude/rules/fronteiras-modulos.md}.
 *
 * <p>{@code Money} chegou aqui no passo R02, junto de {@code Produto.preco} — o primeiro uso real.
 * Ele foi escrito e removido antes disso, por ser codigo morto: abstracao se justifica com uso, nao
 * com previsao. Pelo mesmo motivo ele nasceu sem aritmetica; soma e multiplicacao entram em R09
 * (troco) e R12 (total da venda).
 */
@ApplicationModule(type = ApplicationModule.Type.OPEN, displayName = "Shared")
package br.com.caixasimples.shared;

import org.springframework.modulith.ApplicationModule;
