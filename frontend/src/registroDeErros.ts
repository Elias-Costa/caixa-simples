import { lerTokenDaSessao, sessaoAtual } from './sessao/armazenamento'
import { versaoDoAplicativo } from './versaoDoAplicativo'

/**
 * Quantos erros uma carga da página relata, no máximo. Um erro que se repete a cada desenho, ou uma tela que cai em
 * laço, não enche o log da máquina nem dispara um pedido por segundo.
 */
const MAXIMO_POR_CARGA = 5

/**
 * O que o servidor aceita em cada campo: o mesmo conjunto dos eventos de segurança, sem espaço nem quebra de linha,
 * que forjariam outra linha no log. O resto vira sublinhado, e o servidor nem precisa recusar.
 */
const FORA_DO_PERMITIDO = /[^A-Za-z0-9._:/@%+-]/g

const relatados = new Set<string>()

type DoEvento = { arquivo?: string; linha?: number; coluna?: number }

function limpo(texto: string, tamanho: number): string {
  return texto.replace(FORA_DO_PERMITIDO, '_').slice(0, tamanho)
}

/**
 * O primeiro trecho da pilha que é código do aplicativo, sem a origem: o arquivo, a linha e a coluna. Sem trecho da
 * origem, o erro veio de uma extensão do navegador ou de outro endereço, e não é defeito do aplicativo.
 */
export function localNoAplicativo(pilha: string | undefined, origem: string): string | null {
  for (const linha of (pilha ?? '').split('\n')) {
    const inicio = linha.indexOf(`${origem}/`)
    if (inicio < 0) continue
    const trecho = linha.slice(inicio + origem.length + 1).match(/^[^\s)]+:\d+:\d+/)
    if (trecho) return trecho[0]
  }
  return null
}

/**
 * Manda ao servidor o erro que nenhuma tela tratou: o tipo, o arquivo, a linha e a coluna do código do aplicativo, a
 * tela e a versão. A mensagem não vai, porque pode trazer o que se digitou, e o servidor grava a linha sem a Conta nem
 * o usuário. Sem sessão não há para onde mandar, e sem rede o erro se perde: guardá-lo seria mais um registro no
 * aparelho, para um diagnóstico que o próximo erro igual também dá.
 *
 * O pedido sai por fetch direto, e não pelo cliente da API: a falha do relato não pode pôr o aparelho como sem
 * servidor, nem trocar o token, nem levar a pessoa à entrada. Toda falha dele é engolida.
 */
export function relatarErro(erro: unknown, doEvento: DoEvento = {}): void {
  const origem = location.origin
  let local = localNoAplicativo(erro instanceof Error ? erro.stack : undefined, origem)
  if (!local && doEvento.arquivo?.startsWith(`${origem}/`)) {
    local = `${doEvento.arquivo.slice(origem.length + 1)}:${doEvento.linha ?? 0}:${doEvento.coluna ?? 0}`
  }
  if (!local) return

  const sessao = sessaoAtual()
  const token = sessao ? lerTokenDaSessao(sessao) : null
  if (!token) return

  const tipo = limpo(erro instanceof Error && erro.name ? erro.name : 'Error', 60)
  const chave = `${tipo}|${local}`
  if (relatados.has(chave) || relatados.size >= MAXIMO_POR_CARGA) return
  relatados.add(chave)

  const relato = { tipo, local: limpo(local, 200), tela: limpo(location.pathname, 60), versao: limpo(versaoDoAplicativo(), 40) }
  try {
    void fetch('/api/erros-do-aplicativo', {
      method: 'POST',
      keepalive: true,
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
      body: JSON.stringify(relato),
    }).catch(() => undefined)
  } catch {
    // O fetch indisponível ou recusado na hora também não vira outro erro.
  }
}

/**
 * Liga o relato aos erros que escapam de tudo: a exceção sem tratamento e a promessa rejeitada sem quem a espere. O
 * erro de desenho que derruba a tela chega pelo primeiro, porque o React o entrega à janela.
 */
export function observarErros(): void {
  addEventListener('error', (evento) =>
    relatarErro(evento.error, { arquivo: evento.filename, linha: evento.lineno, coluna: evento.colno }))
  addEventListener('unhandledrejection', (evento) => relatarErro(evento.reason))
}
