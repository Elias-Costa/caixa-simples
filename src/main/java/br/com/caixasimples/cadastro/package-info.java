/**
 * Bounded Context de cadastro: produtos, serviços e clientes.
 *
 * <p>Contém o agregado {@code Produto}, que é raiz e tem {@code MovimentoEstoque} como membro,
 * {@code Cliente} como vertical slice simples, e {@code ModeloProduto}, que é dado de referência da
 * plataforma e uma das três únicas tabelas do sistema fora do filtro de tenant (RF32).
 */
@ApplicationModule(displayName = "Cadastro")
package br.com.caixasimples.cadastro;

import org.springframework.modulith.ApplicationModule;
