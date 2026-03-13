import React, { useEffect, useMemo, useState } from 'https://esm.sh/react@18.3.1';
import { createRoot } from 'https://esm.sh/react-dom@18.3.1/client';

const API = '/api/miniapp';
const tg = window.Telegram?.WebApp;
const getUserId = () => tg?.initDataUnsafe?.user?.id || Number(new URLSearchParams(location.search).get('userId') || 1);

function App() {
  const userId = useMemo(getUserId, []);
  const [data, setData] = useState();
  const [tab, setTab] = useState('race');
  const [message, setMessage] = useState('');
  const [spark, setSpark] = useState(null);

  const refresh = async () => setData(await (await fetch(`${API}/bootstrap?userId=${userId}`)).json());
  useEffect(() => { tg?.ready(); refresh(); }, []);

  const act = async (path, body) => {
    const r = await (await fetch(`${API}/${path}?userId=${userId}`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })).json();
    setMessage(r.message); await refresh(); return r;
  };

  if (!data) return React.createElement('div', { className: 'app' }, 'Загрузка...');

  return React.createElement('div', { className: 'app' },
    React.createElement('header', null,
      React.createElement('div', null, `Баланс: ${data.balance} ⭐`),
      React.createElement('div', null, `Бесплатные бустеры: ${data.freeBoosters}`)
    ),
    React.createElement('nav', null,
      React.createElement('button', { className: `tab ${tab === 'race' ? 'active' : ''}`, onClick: () => setTab('race') }, 'Гонка'),
      React.createElement('button', { className: `tab ${tab === 'account' ? 'active' : ''}`, onClick: () => setTab('account') }, 'Аккаунт')
    ),
    tab === 'race' && React.createElement('section', null,
      React.createElement('h3', null, data.race ? `Гонка #${data.race.matchId} (${data.race.type})` : 'Нет активной гонки'),
      ...(data.race?.units || []).map(u => React.createElement('div', { className: 'card', key: u.playerNumber },
        React.createElement('div', null, `${u.playerName} — ${u.score}`),
        spark === u.playerNumber && React.createElement('div', { className: 'spark' }, '✨'),
        React.createElement('div', { className: 'actions' },
          React.createElement('button', { onClick: () => act('vote', { matchId: data.race.matchId, playerNumber: u.playerNumber, amount: 10 }) }, 'Голос 10⭐'),
          React.createElement('button', { onClick: async () => { await act('boost', { playerNumber: u.playerNumber, type: 'BUST' }); setSpark(u.playerNumber); setTimeout(() => setSpark(null), 700); } }, '🐇'),
          React.createElement('button', { onClick: async () => { await act('boost', { playerNumber: u.playerNumber, type: 'SLOW' }); setSpark(u.playerNumber); setTimeout(() => setSpark(null), 700); } }, '🐢'),
          React.createElement('button', { onClick: async () => { await act('boost', { playerNumber: u.playerNumber, type: 'SHIELD' }); setSpark(u.playerNumber); setTimeout(() => setSpark(null), 700); } }, '🛡')
        )
      ))
    ),
    tab === 'account' && React.createElement('section', null,
      React.createElement('h3', null, 'Управление аккаунтом'),
      React.createElement('div', { className: 'grid' }, ...(data.allEmojis || []).map(name =>
        React.createElement('button', { key: name, onClick: () => act('favorite', { playerName: name }) }, `${name}${data.favoriteEmoji === name ? ' ✅' : ''}`)
      )),
      React.createElement('div', { className: 'row' },
        React.createElement('select', { id: 'queue-select' }, ...(data.allEmojis || []).map(e => React.createElement('option', { key: e }, e))),
        React.createElement('button', { onClick: () => act('queue', { playerName: document.getElementById('queue-select').value }) }, 'В очередь (10⭐)')
      ),
      React.createElement('div', { className: 'row' },
        React.createElement('button', { onClick: () => act('topup', { amount: 100 }) }, 'Пополнить +100⭐'),
        React.createElement('button', { onClick: () => act('withdraw', { amount: 50 }) }, 'Вывести 50⭐')
      ),
      React.createElement('div', { className: 'row' },
        React.createElement('select', { id: 'battle-select' }, ...(data.allEmojis || []).map(e => React.createElement('option', { key: e }, e))),
        React.createElement('button', { onClick: () => act('battle', { playerName: document.getElementById('battle-select').value, stake: 100 }) }, 'Создать батл 100⭐')
      ),
      React.createElement('button', { onClick: async () => setMessage((await (await fetch(`${API}/help`)).json()).message) }, 'Помощь')
    ),
    message && React.createElement('footer', null, message)
  );
}

createRoot(document.getElementById('root')).render(React.createElement(App));
