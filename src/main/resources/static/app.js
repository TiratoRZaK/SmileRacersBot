import React, { useEffect, useMemo, useState } from 'https://esm.sh/react@18.3.1';
import { createRoot } from 'https://esm.sh/react-dom@18.3.1/client';

const h = React.createElement;
const API = '/api/miniapp';
const tg = window.Telegram?.WebApp;
const getUserId = () => tg?.initDataUnsafe?.user?.id || Number(new URLSearchParams(location.search).get('userId') || 1);

function App() {
  const userId = useMemo(getUserId, []);
  const [data, setData] = useState();
  const [tab, setTab] = useState('race');
  const [message, setMessage] = useState('');
  const [spark, setSpark] = useState(null);
  const [queueEmoji, setQueueEmoji] = useState('');
  const [battleEmoji, setBattleEmoji] = useState('');

  const refresh = async () => setData(await (await fetch(`${API}/bootstrap?userId=${userId}`)).json());

  useEffect(() => {
    tg?.ready();
    refresh();
  }, []);

  useEffect(() => {
    if (!data?.allEmojis?.length) return;
    setQueueEmoji(current => current || data.allEmojis[0]);
    setBattleEmoji(current => current || data.allEmojis[0]);
  }, [data]);

  const act = async (path, body) => {
    const r = await (await fetch(`${API}/${path}?userId=${userId}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    })).json();
    setMessage(r.message);
    await refresh();
    return r;
  };

  if (!data) return h('div', { className: 'loading' }, 'Загрузка…');

  const raceUnits = (data.race?.units || []).map(u => h('div', { className: 'unit', key: u.playerNumber },
    h('div', { className: 'unit-head' },
      h('div', { className: 'name' }, u.playerName),
      h('div', { className: 'score' }, u.score)
    ),
    h('div', { className: 'meter' }, h('div', { style: { width: `${Math.min(100, u.score)}%` } })),
    spark === u.playerNumber && h('div', { className: 'spark' }, '✨'),
    h('div', { className: 'actions' },
      h('button', { onClick: () => act('vote', { matchId: data.race.matchId, playerNumber: u.playerNumber, amount: 10 }) }, 'Голос 10⭐'),
      h('button', { onClick: async () => { await act('boost', { playerNumber: u.playerNumber, type: 'BUST' }); setSpark(u.playerNumber); setTimeout(() => setSpark(null), 700); } }, '🐇'),
      h('button', { onClick: async () => { await act('boost', { playerNumber: u.playerNumber, type: 'SLOW' }); setSpark(u.playerNumber); setTimeout(() => setSpark(null), 700); } }, '🐢'),
      h('button', { onClick: async () => { await act('boost', { playerNumber: u.playerNumber, type: 'SHIELD' }); setSpark(u.playerNumber); setTimeout(() => setSpark(null), 700); } }, '🛡')
    )
  ));

  return h('div', { className: 'app' },
    h('div', { className: 'aurora' }),
    h('header', { className: 'top-card' },
      h('div', null, h('span', { className: 'label' }, 'Баланс'), h('b', null, `${data.balance} ⭐`)),
      h('div', null, h('span', { className: 'label' }, 'Бесплатные бустеры'), h('b', null, data.freeBoosters))
    ),
    h('nav', { className: 'tabs' },
      h('button', { className: tab === 'race' ? 'active' : '', onClick: () => setTab('race') }, 'Гонка'),
      h('button', { className: tab === 'account' ? 'active' : '', onClick: () => setTab('account') }, 'Аккаунт')
    ),
    tab === 'race' && h('section', { className: 'panel' },
      h('h2', null, data.race ? `Гонка #${data.race.matchId} · ${data.race.type}` : 'Нет активной гонки'),
      h('p', { className: 'subtitle' }, 'Поддержи фаворита и ускорь гонку бустерами.'),
      ...raceUnits
    ),
    tab === 'account' && h('section', { className: 'panel' },
      h('h2', null, 'Профиль и действия'),
      h('p', { className: 'subtitle' }, 'Выбери любимчика, управляй балансом и создавай батлы.'),
      h('h3', null, 'Любимый эмодзи'),
      h('div', { className: 'grid' }, ...(data.allEmojis || []).map(name => h('button', {
        className: data.favoriteEmoji === name ? 'chip selected' : 'chip',
        key: name,
        onClick: () => act('favorite', { playerName: name })
      }, `${name}${data.favoriteEmoji === name ? ' ✓' : ''}`))),
      h('h3', null, 'Очередь'),
      h('div', { className: 'row' },
        h('select', { value: queueEmoji, onChange: e => setQueueEmoji(e.target.value) }, ...(data.allEmojis || []).map(e => h('option', { key: e }, e))),
        h('button', { onClick: () => act('queue', { playerName: queueEmoji }) }, 'В очередь (10⭐)')
      ),
      h('h3', null, 'Баланс'),
      h('div', { className: 'row' },
        h('button', { onClick: () => act('topup', { amount: 100 }) }, 'Пополнить +100⭐'),
        h('button', { onClick: () => act('withdraw', { amount: 50 }) }, 'Вывести 50⭐')
      ),
      h('h3', null, 'Батл'),
      h('div', { className: 'row' },
        h('select', { value: battleEmoji, onChange: e => setBattleEmoji(e.target.value) }, ...(data.allEmojis || []).map(e => h('option', { key: e }, e))),
        h('button', { onClick: () => act('battle', { playerName: battleEmoji, stake: 100 }) }, 'Создать батл 100⭐')
      ),
      h('button', { className: 'help-btn', onClick: async () => setMessage((await (await fetch(`${API}/help`)).json()).message) }, 'Помощь')
    ),
    message && h('footer', null, message)
  );
}

createRoot(document.getElementById('root')).render(h(App));
