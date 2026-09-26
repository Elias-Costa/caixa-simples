import { Navigate, Route, Routes } from 'react-router'
import { ExigeAdmin } from './shell/ExigeAdmin'
import { ExigeSessao } from './shell/ExigeSessao'
import { Shell } from './shell/Shell'
import { Inicio } from './telas/Inicio'
import { TelaDeLogin } from './telas/TelaDeLogin'
import { TelaDeProdutos } from './telas/TelaDeProdutos'
import { TelaDeClientes } from './telas/TelaDeClientes'
import { TelaDeCaixa } from './telas/TelaDeCaixa'
import { TelaDeVenda } from './telas/TelaDeVenda'
import { TelaDeRelatorios } from './telas/TelaDeRelatorios'
import { TelaDeUsuarios } from './telas/TelaDeUsuarios'
import { TelaDeConfiguracao } from './telas/TelaDeConfiguracao'
import { TelaDeEstoque } from './telas/TelaDeEstoque'
import { TelaDeFiado } from './telas/TelaDeFiado'
import { TelaDeSincronizacao } from './telas/TelaDeSincronizacao'

/**
 * As rotas do aplicativo. Todas, menos o login, vivem dentro do shell e exigem sessão; as do
 * administrador ficam sob a guarda dele. O servidor não conhece estes caminhos: qualquer um
 * deles, pedido direto, recebe o shell e o roteador escolhe a tela.
 */
export function App() {
  return (
    <Routes>
      <Route path="/entrar" element={<TelaDeLogin />} />

      <Route element={<ExigeSessao />}>
        <Route element={<Shell />}>
          <Route index element={<Inicio />} />
          <Route path="vender" element={<TelaDeVenda />} />
          <Route path="caixa" element={<TelaDeCaixa />} />
          <Route path="produtos" element={<TelaDeProdutos />} />
          <Route path="clientes" element={<TelaDeClientes />} />
          <Route path="fiado" element={<TelaDeFiado />} />
          <Route path="sincronizacao" element={<TelaDeSincronizacao />} />

          <Route element={<ExigeAdmin />}>
            <Route path="relatorios" element={<TelaDeRelatorios />} />
            <Route path="usuarios" element={<TelaDeUsuarios />} />
            <Route path="configuracao" element={<TelaDeConfiguracao />} />
          </Route>

          <Route element={<ExigeAdmin comEstoque />}>
            <Route path="estoque" element={<TelaDeEstoque />} />
          </Route>

          <Route path="*" element={<Navigate to="/" replace />} />
        </Route>
      </Route>
    </Routes>
  )
}
