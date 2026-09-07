/**
 * Tipos genuinamente transversais: {@code ContaId}, excecoes comuns e o contexto do tenant.
 * Modulo <strong>aberto</strong> — os outros modulos podem acessar seus subpacotes.
 *
 * <p>Nao e deposito de utilitario: so entra aqui o que mais de um Bounded Context precisa de
 * verdade. Regra em {@code .claude/rules/fronteiras-modulos.md}.
 *
 * <p>A arquitetura §2 tambem cita {@code Money} como tipo deste modulo. Ele ainda nao existe de
 * proposito: nasce no passo R02 do roteiro, junto de {@code Produto.preco}, que e o primeiro uso
 * real. Abstracao se justifica com uso, nao com previsao.
 */
@ApplicationModule(type = ApplicationModule.Type.OPEN, displayName = "Shared")
package br.com.caixasimples.shared;

import org.springframework.modulith.ApplicationModule;
