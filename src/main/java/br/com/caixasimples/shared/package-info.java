/**
 * Tipos genuinamente transversais: {@code ContaId}, {@code Money}, exceções comuns e o contexto do
 * tenant. Módulo <strong>aberto</strong>, ou seja, os outros módulos podem acessar seus subpacotes.
 *
 * <p>Não é depósito de utilitário: só entra aqui o que mais de um Bounded Context precisa de
 * verdade.
 *
 * <p>{@code Money} chegou junto do seu primeiro uso real, que foi o preço do produto. Ele havia
 * sido escrito e removido antes disso, por ser código morto: abstração se justifica com uso, não
 * com previsão. Pelo mesmo motivo nasceu sem aritmética, e cada operação só entrou quando um caso
 * de uso concreto passou a precisar dela.
 */
@ApplicationModule(type = ApplicationModule.Type.OPEN, displayName = "Shared")
package br.com.caixasimples.shared;

import org.springframework.modulith.ApplicationModule;
