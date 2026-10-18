import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { contas, type ConfiguracaoDaConta } from '../api/contas'
import { sessaoAtual } from '../sessao/armazenamento'
import { temRecurso } from '../sessao/Identidade'
import { useSessao } from '../sessao/useSessao'
import { erroDeCadastro } from './erroDeCadastro'

export function TelaDeConfiguracao() {
  const { identidade, atualizarIdentidade } = useSessao()
  const [configuracao, setConfiguracao] = useState<ConfiguracaoDaConta | null>(null)
  const [nomeNegocio, setNomeNegocio] = useState('')
  // Qual chave está sendo salva: as outras ficam paradas, e só a que mudou diz que está salvando.
  const [salvando, setSalvando] = useState<'nome' | 'estoque' | 'nsu' | null>(null)
  const [erro, setErro] = useState<string | null>(null)
  const [aviso, setAviso] = useState<string | null>(null)

  const carregar = useCallback(async () => {
    try {
      const lida = await contas.configuracao()
      setConfiguracao(lida)
      setNomeNegocio(lida.nomeNegocio)
      setErro(null)
    } catch (falha) {
      setErro(erroDeCadastro(falha))
    }
  }, [])

  useEffect(() => { queueMicrotask(() => void carregar()) }, [carregar])

  /**
   * Toda mudança responde a configuração inteira, e a identidade guardada recebe o que ela traz: o
   * cabeçalho e o comprovante mostram o nome do negócio, o menu depende do estoque, e o PDV sem rede,
   * da exigência do NSU.
   */
  async function mudar(chave: 'nome' | 'estoque' | 'nsu', pedido: () => Promise<ConfiguracaoDaConta>) {
    if (!identidade) return
    const sessao = sessaoAtual()
    if (!sessao) return
    setSalvando(chave)
    setErro(null)
    setAviso(null)
    try {
      const nova = await pedido()
      setConfiguracao(nova)
      atualizarIdentidade({
        ...identidade, nomeNegocio: nova.nomeNegocio,
        estoqueHabilitado: nova.estoqueHabilitado, nsuObrigatorio: nova.nsuObrigatorio,
      }, sessao)
      if (chave === 'nome') {
        setNomeNegocio(nova.nomeNegocio)
        setAviso('Nome do negócio corrigido.')
      }
    } catch (falha) {
      setErro(erroDeCadastro(falha))
    } finally {
      setSalvando(null)
    }
  }

  function corrigirNome(evento: FormEvent<HTMLFormElement>) {
    evento.preventDefault()
    const nome = nomeNegocio.trim()
    if (!nome) {
      setAviso(null)
      setErro('Informe o nome do negócio.')
      return
    }
    void mudar('nome', () => contas.corrigirNomeDoNegocio(nome))
  }

  // Ligar exige o estoque do plano; desligar, não: um controle ligado pode ser desligado enquanto
  // não houver movimento, mesmo sem o plano.
  const semPlanoParaLigar = configuracao?.estoqueHabilitado === false && !!identidade
    && !temRecurso(identidade, 'ESTOQUE')

  return <section className="relatorios">
    <h2 className="titulo">Configuração da Conta</h2>
    {erro && <p className="mensagem-erro" role="alert">{erro}</p>}
    {aviso && <p role="status">{aviso}</p>}
    <section className="relatorios__cartao">
      <h3>Nome do negócio</h3>
      {configuracao === null ? <p>Carregando configuração...</p> :
        <form onSubmit={corrigirNome} noValidate>
          <label className="campo"><span className="campo__rotulo">Nome do negócio</span>
            <input className="campo__entrada" value={nomeNegocio} required maxLength={120}
              onChange={(evento) => setNomeNegocio(evento.target.value)} />
          </label>
          <p>É o nome que aparece no cabeçalho e no comprovante. Os outros aparelhos passam a
            mostrá-lo quando o aplicativo for aberto de novo neles, com rede.</p>
          <button className="botao" disabled={salvando !== null}>
            {salvando === 'nome' ? 'Salvando...' : 'Salvar o nome'}
          </button>
        </form>}
    </section>
    <section className="relatorios__cartao">
      <h3>Controle de estoque</h3>
      {configuracao === null ? <p>Carregando configuração...</p> : <>
        <p>{configuracao.estoqueHabilitado ? 'Ligado' : 'Desligado'}</p>
        <p>Quando ligado, vendas de produtos movimentam o saldo e o menu Estoque fica disponível.</p>
        <p>Depois do primeiro movimento de estoque, o controle não pode ser desligado.</p>
        {semPlanoParaLigar && <p>O controle de estoque faz parte do plano Completo, sem
          suspensão. Veja a tela <Link to="/plano">Plano</Link>.</p>}
        <button className="botao" type="button" role="switch" aria-checked={configuracao.estoqueHabilitado}
          disabled={salvando !== null || semPlanoParaLigar}
          onClick={() => void mudar('estoque', () => contas.definirEstoque(!configuracao.estoqueHabilitado))}>
          {salvando === 'estoque' ? 'Salvando...'
            : configuracao.estoqueHabilitado ? 'Desligar controle de estoque' : 'Ligar controle de estoque'}
        </button>
      </>}
    </section>
    <section className="relatorios__cartao">
      <h3>NSU no pagamento em cartão</h3>
      {configuracao === null ? <p>Carregando configuração...</p> : <>
        <p>{configuracao.nsuObrigatorio ? 'Obrigatório' : 'Opcional'}</p>
        <p>O NSU é o número impresso no comprovante da maquininha. Com ele, a conferência do cartão,
          em Relatórios, bate cada pagamento com o extrato da operadora.</p>
        <p>Quando obrigatório, a venda e o recebimento de fiado em cartão só são registrados com o
          NSU. O que já foi lançado sem ele continua valendo, e a venda feita sem rede que chegar sem
          NSU aparece em Sincronização para conferência.</p>
        <button className="botao" type="button" role="switch" aria-checked={configuracao.nsuObrigatorio}
          disabled={salvando !== null}
          onClick={() => void mudar('nsu', () => contas.definirNsu(!configuracao.nsuObrigatorio))}>
          {salvando === 'nsu' ? 'Salvando...'
            : configuracao.nsuObrigatorio ? 'Deixar o NSU opcional' : 'Exigir o NSU no cartão'}
        </button>
      </>}
    </section>
  </section>
}
