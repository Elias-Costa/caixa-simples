/**
 * Bounded Context de cadastro: produtos, servicos e clientes.
 *
 * <p>Contem o agregado {@code Produto} (raiz, com {@code MovimentoEstoque} como membro),
 * {@code Cliente} como vertical slice simples, e {@code ModeloProduto} — a unica entidade do
 * sistema sem {@code conta_id}, por ser dado de referencia da plataforma (RF32).
 */
@ApplicationModule(displayName = "Cadastro")
package br.com.caixasimples.cadastro;

import org.springframework.modulith.ApplicationModule;
