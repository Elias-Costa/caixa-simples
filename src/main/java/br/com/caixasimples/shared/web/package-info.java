/**
 * O que a camada HTTP tem de transversal: a tradução de exceção em resposta.
 *
 * <p>Mora no módulo aberto porque as exceções que ela traduz, a de acesso negado e as de
 * contexto sem usuário ou sem conta, moram aqui, e porque todo controller de todo módulo passa
 * por ela. Não há controller neste pacote: cada rota pertence ao pacote web do módulo dono.
 */
package br.com.caixasimples.shared.web;
