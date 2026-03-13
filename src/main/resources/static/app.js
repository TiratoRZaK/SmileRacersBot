import React, { useEffect, useMemo, useState } from 'https://esm.sh/react@18.3.1';
import { createRoot } from 'https://esm.sh/react-dom@18.3.1/client';

const h = React.createElement;
const API = '/api/miniapp';
const tg = window.Telegram?.WebApp;
const WITHDRAW_MIN = 100;
const TOPUP_MIN = 1;
const POLL_INTERVAL_MS = 3500;
const getUserId = () => tg?.initDataUnsafe?.user?.id || Number(new URLSearchParams(location.search).get('userId') || 1);

function App() {
  const userId = useMemo(getUserId, []);
  const [data, setData] = useState();
  const [activeWithdraws, setActiveWithdraws] = useState([]);
  const [tab, setTab] = useState('race');
  const [message, setMessage] = useState('');
  const [spark, setSpark] = useState(null);
  const [battleEmoji, setBattleEmoji] = useState('');
  const [voteAmount, setVoteAmount] = useState(10);
  const [topupAmount, setTopupAmount] = useState(100);
  const [withdrawAmount, setWithdrawAmount] = useState(100);
  const [voteModalUnit, setVoteModalUnit] = useState(null);
  const [favoriteIndex, setFavoriteIndex] = useState(0);

  const refresh = async (silent = false) => {
    const [bootstrapRes, withdrawsRes] = await Promise.all([
      fetch(`${API}/bootstrap?userId=${userId}`),
      fetch(`${API}/withdraw/active?userId=${userId}`)
    ]);
    const bootstrapData = await bootstrapRes.json();
    setData(bootstrapData);
    const withdrawData = await withdrawsRes.json();
    setActiveWithdraws(withdrawData.items || []);
    if (!silent && !bootstrapData.race && tab === 'race') {
      setMessage('Сейчас нет активной гонки. Обновим автоматически, как только стартует следующая.');
    }
  };

  useEffect(() => {
    tg?.ready();
    refresh();
  }, []);

  useEffect(() => {
    const timer = setInterval(() => {
      refresh(true).catch(() => null);
    }, POLL_INTERVAL_MS);
    return () => clearInterval(timer);
  }, [userId, tab]);

  useEffect(() => {
    if (!data?.allEmojis?.length) return;
    setBattleEmoji(current => current || data.allEmojis[0]);
    const currentFavorite = data.favoriteEmoji || data.allEmojis[0];
    const idx = Math.max(0, data.allEmojis.indexOf(currentFavorite));
    setFavoriteIndex(idx);
  }, [data]);

  const act = async (path, body) => {
    const r = await (await fetch(`${API}/${path}?userId=${userId}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    })).json();
    setMessage(r.message);
    if (r.invoiceLink) {
      tg?.openLink ? tg.openLink(r.invoiceLink) : window.open(r.invoiceLink, '_blank');
    }
    await refresh(true);
    return r;
  };

  const openHelp = async () => {
    const response = await fetch(`${API}/help`);
    const payload = await response.json();
    const link = payload?.message;
    if (link && /^https?:\/\//.test(link)) {
      tg?.openLink ? tg.openLink(link) : window.open(link, '_blank');
      return;
    }
    setMessage('Ссылка на поддержку временно недоступна.');
  };

  const voteAction = async () => {
    if (!data?.race || !voteModalUnit) return;
    await act('vote', { matchId: data.race.matchId, playerNumber: voteModalUnit.playerNumber, amount: voteAmount });
    setVoteModalUnit(null);
  };

  if (!data) return h('div', { className: 'loading' }, 'Загрузка…');

  const raceEnded = !data.race || data.race.status !== 'CREATED';
  const maxWithdraw = data?.balance || WITHDRAW_MIN;
  const clampedWithdraw = Math.max(WITHDRAW_MIN, Math.min(withdrawAmount || WITHDRAW_MIN, maxWithdraw));

  const raceUnits = (data.race?.units || []).map(u => h('div', { className: 'unit', key: u.playerNumber },
    h('div', { className: 'unit-head' },
      h('div', { className: 'name' }, u.playerName),
      h('div', { className: 'score' }, u.score)
    ),
    h('div', { className: 'meter' }, h('div', { style: { width: `${Math.min(100, u.score)}%` } })),
    spark === u.playerNumber && h('div', { className: 'spark' }, '✨'),
    h('div', { className: 'actions' },
      h('button', { disabled: raceEnded, onClick: () => setVoteModalUnit(u) }, 'Отдать голос'),
      h('button', { disabled: raceEnded, onClick: async () => { await act('boost', { playerNumber: u.playerNumber, type: 'BUST' }); setSpark(u.playerNumber); setTimeout(() => setSpark(null), 700); } }, '🐇'),
      h('button', { disabled: raceEnded, onClick: async () => { await act('boost', { playerNumber: u.playerNumber, type: 'SLOW' }); setSpark(u.playerNumber); setTimeout(() => setSpark(null), 700); } }, '🐢'),
      h('button', { disabled: raceEnded, onClick: async () => { await act('boost', { playerNumber: u.playerNumber, type: 'SHIELD' }); setSpark(u.playerNumber); setTimeout(() => setSpark(null), 700); } }, '🛡')
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
      h('p', { className: 'subtitle' }, 'Голосование открыто только до старта. Экран синхронизируется каждые несколько секунд.'),
      data.race && raceEnded && h('p', { className: 'badge' }, 'Гонка уже началась или завершилась — голосование закрыто.'),
      ...raceUnits
    ),
    tab === 'account' && h('section', { className: 'panel' },
      h('h2', null, 'Профиль и действия'),
      h('h3', null, 'Любимый смайл'),
      h('div', { className: 'emoji-slider-wrap' },
        h('input', {
          type: 'range',
          min: 0,
          max: Math.max(0, data.allEmojis.length - 1),
          value: favoriteIndex,
          onChange: e => setFavoriteIndex(Number(e.target.value)),
          className: 'emoji-slider'
        }),
        h('div', { className: 'emoji-preview' }, data.allEmojis[favoriteIndex]),
        h('div', { className: 'slider-meta' }, `Смайл ${favoriteIndex + 1} из ${data.allEmojis.length}`),
        h('div', { className: 'row slider-controls' },
          h('button', { disabled: favoriteIndex <= 0, onClick: () => setFavoriteIndex(Math.max(0, favoriteIndex - 1)) }, '← Назад'),
          h('button', { disabled: favoriteIndex >= data.allEmojis.length - 1, onClick: () => setFavoriteIndex(Math.min(data.allEmojis.length - 1, favoriteIndex + 1)) }, 'Вперёд →')
        ),
        h('div', { className: 'row' },
          h('button', { onClick: () => act('favorite', { playerName: data.allEmojis[favoriteIndex] }) }, 'Сохранить любимый смайл')
        )
      ),
      h('p', { className: 'subtitle' }, 'Смена любимого смайла — 150⭐ (первый выбор бесплатный).'),
      h('h3', null, 'Управление очередью'),
      h('div', { className: 'row' },
        h('button', { disabled: !data.favoriteEmoji, onClick: () => act('queue', { playerName: data.favoriteEmoji }) }, 'Добавить любимый смайл в очередь (10⭐)')
      ),
      h('h3', null, 'Баланс'),
      h('div', { className: 'row' },
        h('input', { className: 'field', type: 'number', min: TOPUP_MIN, step: 1, value: topupAmount, onChange: e => setTopupAmount(Math.max(TOPUP_MIN, Number(e.target.value || TOPUP_MIN))) }),
        h('button', { onClick: () => act('topup', { amount: topupAmount }) }, 'Пополнить через ⭐')
      ),
      h('h3', null, 'Вывод'),
      h('div', { className: 'row' },
        h('input', {
          className: 'field',
          type: 'number',
          min: WITHDRAW_MIN,
          max: maxWithdraw,
          step: 1,
          value: withdrawAmount,
          onChange: e => setWithdrawAmount(Number(e.target.value || WITHDRAW_MIN))
        }),
        h('button', { disabled: clampedWithdraw > maxWithdraw, onClick: () => act('withdraw', { amount: clampedWithdraw }) }, 'Создать запрос на вывод')
      ),
      h('p', { className: 'subtitle' }, `Доступно к выводу: до ${maxWithdraw} ⭐. Минимум: ${WITHDRAW_MIN} ⭐.`),
      h('div', { className: 'grid' }, ...activeWithdraws.map(w => h('button', {
        key: w.id,
        className: 'chip',
        onClick: () => act('withdraw/cancel', { requestId: w.id })
      }, `Отменить вывод #${w.id} (${w.amount}⭐)`))),
      h('h3', null, 'Батл'),
      h('div', { className: 'row' },
        h('select', { value: battleEmoji, onChange: e => setBattleEmoji(e.target.value) }, ...(data.allEmojis || []).map(e => h('option', { key: e }, e))),
        h('button', { onClick: () => act('battle', { playerName: battleEmoji, stake: 100 }) }, 'Создать батл 100⭐')
      ),
      h('button', { className: 'help-btn', onClick: openHelp }, 'Связаться с поддержкой')
    ),
    voteModalUnit && h('div', { className: 'modal-backdrop', onClick: () => setVoteModalUnit(null) },
      h('div', { className: 'modal', onClick: e => e.stopPropagation() },
        h('h3', null, 'Отдать голос'),
        h('p', { className: 'subtitle' }, `Вы выбрали: ${voteModalUnit.playerName}`),
        h('input', { className: 'field', type: 'number', min: 1, step: 1, value: voteAmount, onChange: e => setVoteAmount(Math.max(1, Number(e.target.value || 1))) }),
        h('div', { className: 'actions' },
          h('button', { onClick: () => setVoteModalUnit(null) }, 'Отмена'),
          h('button', { onClick: voteAction }, 'Подтвердить')
        )
      )
    ),
    message && h('footer', null, message)
  );
}

createRoot(document.getElementById('root')).render(h(App));
