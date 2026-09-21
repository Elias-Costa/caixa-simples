/**
 * O que a camada HTTP tem de transversal: a tradução de exceção em resposta e a entrega do
 * aplicativo.
 *
 * <p>Mora no módulo aberto porque as exceções que ela traduz, a de acesso negado e as de
 * contexto sem usuário ou sem conta, moram aqui, e porque todo controller de todo módulo passa
 * por ela. O shell do PWA também é servido daqui, porque não pertence a módulo nenhum: é a mesma
 * página para toda tela, e quem decide a tela é o roteador do cliente. Não há controller neste
 * pacote: cada rota da API pertence ao pacote web do módulo dono.
 */
package br.com.caixasimples.shared.web;
