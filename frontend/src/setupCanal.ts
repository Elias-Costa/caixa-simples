/**
 * Um BroadcastChannel só deste arquivo de teste, instalado antes de qualquer módulo do aplicativo abrir o
 * dele. O do Node entrega a mensagem a todo canal com o mesmo nome no processo, inclusive ao de um arquivo de
 * teste que rodou antes; este entrega só aos canais abertos aqui, nunca a quem enviou, e depois da tarefa em
 * curso, como o do navegador. O teste faz o papel da outra aba abrindo um canal com o mesmo nome.
 */
class CanalDeTeste {
  private static readonly abertos = new Map<string, Set<CanalDeTeste>>()
  private readonly ouvintes = new Set<(evento: MessageEvent) => void>()
  private fechado = false
  readonly name: string
  onmessage: ((evento: MessageEvent) => void) | null = null

  constructor(name: string) {
    this.name = name
    const doNome = CanalDeTeste.abertos.get(name) ?? new Set<CanalDeTeste>()
    doNome.add(this)
    CanalDeTeste.abertos.set(name, doNome)
  }

  postMessage(dados: unknown): void {
    if (this.fechado) throw new DOMException('O canal está fechado.', 'InvalidStateError')
    for (const outro of CanalDeTeste.abertos.get(this.name) ?? []) {
      if (outro === this) continue
      const copia = structuredClone(dados)
      queueMicrotask(() => outro.entregar(copia))
    }
  }

  addEventListener(tipo: string, ouvinte: (evento: MessageEvent) => void): void {
    if (tipo === 'message') this.ouvintes.add(ouvinte)
  }

  removeEventListener(tipo: string, ouvinte: (evento: MessageEvent) => void): void {
    if (tipo === 'message') this.ouvintes.delete(ouvinte)
  }

  close(): void {
    this.fechado = true
    CanalDeTeste.abertos.get(this.name)?.delete(this)
  }

  private entregar(dados: unknown): void {
    if (this.fechado) return
    const evento = new MessageEvent('message', { data: dados })
    this.onmessage?.(evento)
    for (const ouvinte of [...this.ouvintes]) ouvinte(evento)
  }
}

globalThis.BroadcastChannel = CanalDeTeste as unknown as typeof BroadcastChannel
