import { aoAvisoDeOutraAba, avisarAsOutrasAbas } from '../canalDasAbas'

/**
 * Se este aparelho alcança o servidor, como ele sabe agora.
 *
 * - com-servidor: o navegador tem rede, e a última chamada teve resposta.
 * - sem-rede: o navegador diz que não há rede.
 * - sem-servidor: o navegador tem rede, mas a última chamada ficou sem resposta.
 *
 * O navegador só percebe a rede que caiu no próprio aparelho. O roteador sem internet, a operadora
 * fora e a aplicação parada para a troca de versão deixam o navegador dizendo que há rede, e é a
 * chamada sem resposta que conta isso. Quem marca é o cliente HTTP, a cada chamada: sem resposta,
 * o aparelho passa a operar pela fila até uma chamada seguinte responder.
 *
 * O estado fica na memória e vale para as abas do aplicativo neste navegador, que usam a mesma rede e o
 * mesmo servidor. A chamada que muda o estado avisa as outras abas, com o momento em que terminou, e vale o
 * fato mais novo: quem recebe só troca o próprio estado por um aviso mais novo que o último fato que conhece,
 * e não repassa. Só uma chamada de verdade muda o estado, e por isso uma aba não desliga e religa a outra em
 * laço. A aba que abre começa com servidor, e a primeira chamada dela já diz como está.
 */
export type EstadoDaConexao = 'com-servidor' | 'sem-rede' | 'sem-servidor'

let semResposta = false
// Quando terminou a chamada mais recente de que esta aba sabe, dela ou de outra aba.
let momentoDoUltimoFato = 0
let ultimoAvisado: EstadoDaConexao | null = null
const ouvintes = new Set<() => void>()

export function estadoDaConexao(): EstadoDaConexao {
  if (!navigator.onLine) return 'sem-rede'
  return semResposta ? 'sem-servidor' : 'com-servidor'
}

/** O que a fila e as telas perguntam antes de chamar o servidor. */
export function comServidor(): boolean {
  return estadoDaConexao() === 'com-servidor'
}

/** A chamada ficou sem resposta: falha do fetch, tempo esgotado ou o proxy sem a aplicação. */
export function registrarSemResposta(): void {
  registrarFato(true)
}

/** Qualquer resposta da aplicação, mesmo de erro, diz que ela está de pé. */
export function registrarResposta(): void {
  registrarFato(false)
}

function registrarFato(semRespostaAgora: boolean): void {
  const em = Date.now()
  const mudou = semResposta !== semRespostaAgora
  semResposta = semRespostaAgora
  momentoDoUltimoFato = em
  if (mudou) avisarAsOutrasAbas({ tipo: 'conexao', semResposta: semRespostaAgora, em })
  avisarSeMudou()
}

/**
 * Avisa quando o estado muda pela resposta de uma chamada e a cada evento de rede do navegador, que
 * pode repetir o estado. Devolve o que desliga o aviso.
 */
export function aoMudarConexao(ouvinte: () => void): () => void {
  ouvintes.add(ouvinte)
  return () => { ouvintes.delete(ouvinte) }
}

// Cada chamada marca o estado, e só a que o muda avisa: a tela não se redesenha a cada resposta.
function avisarSeMudou(): void {
  if (estadoDaConexao() === ultimoAvisado) return
  avisar()
}

function avisar(): void {
  ultimoAvisado = estadoDaConexao()
  for (const ouvinte of [...ouvintes]) ouvinte()
}

// A rede do navegador muda o estado sem chamada nenhuma; os dois eventos valem para o aplicativo
// inteiro, e por isso ficam ligados desde que este arquivo é carregado.
if (typeof window !== 'undefined') {
  window.addEventListener('online', avisar)
  window.addEventListener('offline', avisar)
}

// O aviso de outra aba muda o estado daqui como uma chamada desta mudaria, e não volta ao canal.
aoAvisoDeOutraAba((aviso) => {
  if (aviso.tipo !== 'conexao' || aviso.em <= momentoDoUltimoFato) return
  momentoDoUltimoFato = aviso.em
  semResposta = aviso.semResposta
  avisarSeMudou()
})
