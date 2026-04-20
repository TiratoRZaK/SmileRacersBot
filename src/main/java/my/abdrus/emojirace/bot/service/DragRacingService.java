package my.abdrus.emojirace.bot.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import my.abdrus.emojirace.api.dto.MiniAppDtos;
import my.abdrus.emojirace.bot.entity.Account;
import my.abdrus.emojirace.bot.exception.PaymentException;
import my.abdrus.emojirace.bot.repository.AccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class DragRacingService {

    private static final long AIRBAG_RUBIES_COST = 100L;
    private static final int AIRBAG_BUSTERS_COST = 5;
    private final ConcurrentHashMap<Long, DragRun> runsByUserId = new ConcurrentHashMap<>();

    private final AccountService accountService;
    private final AccountRepository accountRepository;

    public DragRacingService(AccountService accountService, AccountRepository accountRepository) {
        this.accountService = accountService;
        this.accountRepository = accountRepository;
    }

    public MiniAppDtos.DragRaceStateResponse start(Long userId, MiniAppDtos.DragRaceStartRequest request) {
        if (request == null || request.stake() == null || request.stake() < 1 || !StringUtils.hasText(request.difficulty())) {
            return MiniAppDtos.DragRaceStateResponse.error("Некорректные параметры для старта драг-рейсинга.");
        }
        Difficulty difficulty = Difficulty.fromCode(request.difficulty());
        if (difficulty == null) {
            return MiniAppDtos.DragRaceStateResponse.error("Неизвестная сложность. Используйте EASY/NORMAL/HARD/EXTREME.");
        }

        AirbagSource airbagSource = AirbagSource.fromCode(request.buyAirbagBy());
        Account account = accountService.getByUserId(userId);
        long balance = account.getBalance() == null ? 0L : account.getBalance();
        if (request.stake() > balance) {
            return MiniAppDtos.DragRaceStateResponse.error("Ставка не может быть больше вашего баланса.");
        }
        if (airbagSource == AirbagSource.FREE_BUSTS && (account.getFreeBustCount() == null || account.getFreeBustCount() < AIRBAG_BUSTERS_COST)) {
            return MiniAppDtos.DragRaceStateResponse.error("Недостаточно бесплатных бустеров для покупки подушки.");
        }

        long totalCost = request.stake() + (airbagSource == AirbagSource.RUBIES ? AIRBAG_RUBIES_COST : 0L);
        if (totalCost > balance) {
            return MiniAppDtos.DragRaceStateResponse.error("Недостаточно баланса для старта с выбранной подушкой.");
        }
        try {
            accountService.pay(userId, totalCost);
        } catch (PaymentException e) {
            return MiniAppDtos.DragRaceStateResponse.error(e.getMessage());
        }

        if (airbagSource == AirbagSource.FREE_BUSTS) {
            accountRepository.addFreeBustCount(userId, -AIRBAG_BUSTERS_COST);
        }

        List<DragEventDefinition> events = generateEventSequence(difficulty.eventsCount);
        DragRun run = new DragRun(
                UUID.randomUUID().toString(),
                userId,
                difficulty,
                request.stake(),
                airbagSource != AirbagSource.NONE,
                false,
                false,
                false,
                "Забег начался. Выберите первое действие.",
                events,
                0,
                1d,
                0L
        );
        runsByUserId.put(userId, run);
        return toState(run, true);
    }

    public MiniAppDtos.DragRaceStateResponse getState(Long userId) {
        DragRun run = runsByUserId.get(userId);
        if (run == null) {
            return MiniAppDtos.DragRaceStateResponse.empty("Активного драг-забега нет. Нажмите «Старт драг-рейсинга».");
        }
        return toState(run, true);
    }

    public MiniAppDtos.DragRaceStateResponse applyChoice(Long userId, String branchId) {
        DragRun run = runsByUserId.get(userId);
        if (run == null) {
            return MiniAppDtos.DragRaceStateResponse.empty("Сначала начните драг-забег.");
        }
        if (run.finished) {
            return toState(run, true);
        }
        if (!StringUtils.hasText(branchId)) {
            return MiniAppDtos.DragRaceStateResponse.error("Не выбрана ветка действия.");
        }

        DragEventDefinition event = run.events.get(run.currentEventIndex);
        DragBranchDefinition selectedBranch = event.branches.stream()
                .filter(branch -> Objects.equals(branch.id, branchId))
                .findFirst()
                .orElse(null);
        if (selectedBranch == null) {
            return MiniAppDtos.DragRaceStateResponse.error("Ветка действия не найдена.");
        }

        double stageProgress = run.events.size() <= 1 ? 0d : (double) run.currentEventIndex / (double) (run.events.size() - 1);
        double stakePenalty = stakePenalty(run.stake);
        double successChance = getEffectiveSuccessChance(selectedBranch, stageProgress, stakePenalty);
        double fatalChance = getEffectiveFatalChance(selectedBranch, stakePenalty);
        boolean success = ThreadLocalRandom.current().nextDouble() <= successChance;

        if (success) {
            run.multiplier *= selectedBranch.multiplierOnSuccess;
            run.message = "✅ " + selectedBranch.successText;
            run.currentEventIndex += 1;
        } else {
            run.multiplier *= selectedBranch.multiplierOnFail;
            boolean fatal = ThreadLocalRandom.current().nextDouble() <= fatalChance;
            if (fatal) {
                if (run.airbagAvailable) {
                    run.airbagAvailable = false;
                    run.airbagConsumed = true;
                    run.multiplier *= 0.82;
                    run.message = "🛟 Подушка спасла забег: " + selectedBranch.failText;
                    run.currentEventIndex += 1;
                } else {
                    run.finished = true;
                    run.success = false;
                    run.payout = 0L;
                    run.message = "💥 Забег завершён: " + selectedBranch.failText;
                }
            } else {
                run.message = "⚠️ " + selectedBranch.failText;
                run.currentEventIndex += 1;
            }
        }

        if (!run.finished && run.currentEventIndex >= run.events.size()) {
            run.finished = true;
            run.success = true;
            long payout = Math.max(0L, Math.round(run.stake * run.difficulty.baseMultiplier * run.multiplier));
            run.payout = payout;
            if (payout > 0) {
                accountService.addBalance(userId, payout);
            }
            run.message = "🏁 Финиш! Награда: " + payout + " 💎.";
        }

        return toState(run, true);
    }

    private MiniAppDtos.DragRaceStateResponse toState(DragRun run, boolean success) {
        MiniAppDtos.DragRaceEventCard currentEvent = null;
        if (!run.finished && run.currentEventIndex < run.events.size()) {
            DragEventDefinition event = run.events.get(run.currentEventIndex);
            double stageProgress = run.events.size() <= 1 ? 0d : (double) run.currentEventIndex / (double) (run.events.size() - 1);
            double stakePenalty = stakePenalty(run.stake);
            currentEvent = new MiniAppDtos.DragRaceEventCard(
                    event.code,
                    event.title,
                    event.description,
                    event.branches.stream()
                            .map(branch -> new MiniAppDtos.DragRaceBranchCard(
                                    branch.id,
                                    branch.title,
                                    branch.hint,
                                    getEffectiveSuccessChance(branch, stageProgress, stakePenalty),
                                    getEffectiveFatalChance(branch, stakePenalty),
                                    computeProjectedReward(run, run.multiplier * branch.multiplierOnSuccess),
                                    computeProjectedReward(run, run.multiplier * branch.multiplierOnFail),
                                    branch.successText,
                                    branch.failText
                            ))
                            .toList()
            );
        }

        long projectedReward = Math.max(0L, Math.round(run.stake * run.difficulty.baseMultiplier * run.multiplier));
        return new MiniAppDtos.DragRaceStateResponse(
                success,
                run.message,
                run.runId,
                run.difficulty.code,
                run.stake,
                run.currentEventIndex + 1,
                run.events.size(),
                run.finished,
                run.success,
                run.airbagAvailable,
                run.airbagConsumed,
                projectedReward,
                run.payout,
                currentEvent
        );
    }

    private List<DragEventDefinition> generateEventSequence(int count) {
        List<DragEventDefinition> pool = new ArrayList<>(DEFAULT_EVENTS);
        Collections.shuffle(pool);
        return new ArrayList<>(pool.subList(0, Math.min(count, pool.size())));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double stakePenalty(long stake) {
        if (stake >= 300) return 0.10;
        if (stake >= 150) return 0.06;
        if (stake >= 50) return 0.03;
        return 0d;
    }

    private static double getEffectiveSuccessChance(DragBranchDefinition branch, double stageProgress, double stakePenalty) {
        double stagePenalty = "A".equalsIgnoreCase(branch.id) ? stageProgress * 0.34 : stageProgress * 0.10;
        return clamp(branch.successChance - stagePenalty - (stakePenalty * branch.stakePressureCoeff), 0.05, 0.99);
    }

    private static double getEffectiveFatalChance(DragBranchDefinition branch, double stakePenalty) {
        return clamp(branch.fatalOnFailChance + (stakePenalty * branch.fatalPressureCoeff), 0d, 0.95);
    }

    private static long computeProjectedReward(DragRun run, double targetMultiplier) {
        return Math.max(0L, Math.round(run.stake * run.difficulty.baseMultiplier * targetMultiplier));
    }

    private enum Difficulty {
        EASY("EASY", 4, 1.55),
        NORMAL("NORMAL", 5, 1.75),
        HARD("HARD", 6, 2.10),
        EXTREME("EXTREME", 7, 2.60);

        private final String code;
        private final int eventsCount;
        private final double baseMultiplier;

        Difficulty(String code, int eventsCount, double baseMultiplier) {
            this.code = code;
            this.eventsCount = eventsCount;
            this.baseMultiplier = baseMultiplier;
        }

        private static Difficulty fromCode(String raw) {
            if (!StringUtils.hasText(raw)) return null;
            for (Difficulty value : values()) {
                if (value.code.equalsIgnoreCase(raw)) return value;
            }
            return null;
        }
    }

    private enum AirbagSource {
        NONE,
        RUBIES,
        FREE_BUSTS;

        private static AirbagSource fromCode(String raw) {
            if (!StringUtils.hasText(raw)) return NONE;
            for (AirbagSource value : values()) {
                if (value.name().equalsIgnoreCase(raw)) return value;
            }
            return NONE;
        }
    }

    private static class DragRun {
        private final String runId;
        private final Long userId;
        private final Difficulty difficulty;
        private final long stake;
        private boolean airbagAvailable;
        private boolean airbagConsumed;
        private boolean finished;
        private boolean success;
        private String message;
        private final List<DragEventDefinition> events;
        private int currentEventIndex;
        private double multiplier;
        private long payout;

        private DragRun(
                String runId,
                Long userId,
                Difficulty difficulty,
                long stake,
                boolean airbagAvailable,
                boolean airbagConsumed,
                boolean finished,
                boolean success,
                String message,
                List<DragEventDefinition> events,
                int currentEventIndex,
                double multiplier,
                long payout
        ) {
            this.runId = runId;
            this.userId = userId;
            this.difficulty = difficulty;
            this.stake = stake;
            this.airbagAvailable = airbagAvailable;
            this.airbagConsumed = airbagConsumed;
            this.finished = finished;
            this.success = success;
            this.message = message;
            this.events = events;
            this.currentEventIndex = currentEventIndex;
            this.multiplier = multiplier;
            this.payout = payout;
        }
    }

    private record DragEventDefinition(
            String code,
            String title,
            String description,
            List<DragBranchDefinition> branches
    ) {}

    private record DragBranchDefinition(
            String id,
            String title,
            String hint,
            double previewSuccessChance,
            double successChance,
            double fatalOnFailChance,
            double stakePressureCoeff,
            double fatalPressureCoeff,
            double multiplierOnSuccess,
            double multiplierOnFail,
            String successText,
            String failText
    ) {}

    private static final List<DragEventDefinition> DEFAULT_EVENTS = List.of(
            new DragEventDefinition("E01", "Резкий поворот", "Впереди крутой вираж с гравием на внешней дуге: любое лишнее движение рулём может сорвать заднюю ось.", List.of(
                    new DragBranchDefinition("A", "Притормозить", "Безопасно, но медленнее", 0.88, 0.88, 0.08, 0.85, 0.65, 0.92, 0.92, "Поворот пройден аккуратно.", "Вы сбавили темп."),
                    new DragBranchDefinition("B", "Дрифт на ручнике", "Риск ради темпа", 0.62, 0.62, 0.22, 1.00, 1.20, 1.14, 0.78, "Идеальный дрифт!", "Машину сорвало на обочину.")
            )),
            new DragEventDefinition("E02", "Олень на дороге", "Из тумана прямо на трассу выбегает олень, а за ним мелькает ещё несколько силуэтов — окно для манёвра очень короткое.", List.of(
                    new DragBranchDefinition("A", "Тормозить", "Надёжно", 0.88, 0.88, 0.08, 0.85, 0.65, 0.94, 0.94, "Олень цел, вы тоже.", "Потеря времени на торможении."),
                    new DragBranchDefinition("B", "Проскочить", "Ставка на реакцию", 0.57, 0.57, 0.26, 1.00, 1.20, 1.16, 0.80, "Проскочили в сантиметрах!", "Олень запаниковал и выбежал обратно.")
            )),
            new DragEventDefinition("E03", "Семья ежей", "На прогретом асфальте растянулась целая семья ежей: резкий манёвр спасёт их, но может стоить вам сцепления и темпа.", List.of(
                    new DragBranchDefinition("A", "Объехать", "Почти безопасно", 0.88, 0.88, 0.08, 0.85, 0.65, 0.97, 0.97, "Аккуратно объехали.", "Сбавили темп."),
                    new DragBranchDefinition("B", "Пронестись", "Риск ради ускорения", 0.54, 0.54, 0.30, 1.00, 1.20, 1.20, 0.74, "Ежи остались позади.", "Медведь-товарищ не оценил манёвр.")
            )),
            new DragEventDefinition("E04", "Трамплин", "Перед вами импровизированный трамплин после ремонта полотна: можно выиграть секунды в полёте, но ошибка на приземлении критична.", List.of(
                    new DragBranchDefinition("A", "Сбросить скорость", "Контроль", 0.88, 0.88, 0.08, 0.85, 0.65, 0.93, 0.93, "Ровное приземление.", "Слишком осторожный заход."),
                    new DragBranchDefinition("B", "Полный прыжок", "Шоу и риск", 0.56, 0.56, 0.28, 1.00, 1.20, 1.21, 0.76, "Эпичный прыжок!", "Жёсткая посадка.")
            )),
            new DragEventDefinition("E05", "Песчаная буря", "Порывы ветра несут плотную стену песка, и фары почти не пробивают её — ориентиры исчезают каждые пару метров.", List.of(
                    new DragBranchDefinition("A", "По приборам", "Стабильно", 0.88, 0.88, 0.08, 0.85, 0.65, 0.94, 0.94, "Сохранили контроль.", "Темп упал."),
                    new DragBranchDefinition("B", "Газ в пол", "Ва-банк", 0.50, 0.50, 0.33, 1.00, 1.20, 1.23, 0.75, "Пронеслись сквозь бурю!", "Песок полностью ослепил.")
            )),
            new DragEventDefinition("E06", "Масло на трассе", "После аварии участок залит маслом: дорога блестит как лёд, а любое резкое действие мгновенно уводит машину в занос.", List.of(
                    new DragBranchDefinition("A", "Объезд", "Потеряете темп", 0.88, 0.88, 0.08, 0.85, 0.65, 0.95, 0.95, "Ушли от скольжения.", "Медленный объезд."),
                    new DragBranchDefinition("B", "Контр-руление на грани", "Нужны нервы", 0.52, 0.52, 0.31, 1.00, 1.20, 1.22, 0.74, "Вытащили занос!", "Машину развернуло.")
            )),
            new DragEventDefinition("E07", "Яма на трассе", "На узком отрезке зияет глубокая яма, слева бетонный барьер без запаса, справа обочина с рыхлым грунтом.", List.of(
                    new DragBranchDefinition("A", "Обрулить", "Надёжный выбор", 0.88, 0.88, 0.08, 0.85, 0.65, 0.95, 0.95, "Аккуратный объезд.", "Скорость просела."),
                    new DragBranchDefinition("B", "Перелететь", "Риск ради времени", 0.49, 0.49, 0.34, 1.00, 1.20, 1.24, 0.72, "Перелёт получился.", "Приземление оказалось провальным.")
            )),
            new DragEventDefinition("E08", "Фура закрыла обзор", "Тяжёлая фура ползёт впереди и полностью перекрывает обзор, а встречная полоса просматривается только в последний момент.", List.of(
                    new DragBranchDefinition("A", "Держать дистанцию", "Минимум риска", 0.88, 0.88, 0.08, 0.85, 0.65, 0.94, 0.94, "Безопасно переждали момент.", "Потеряли время."),
                    new DragBranchDefinition("B", "Обгон вслепую", "Самый опасный манёвр", 0.46, 0.46, 0.39, 1.00, 1.20, 1.28, 0.70, "Обгон удался чудом!", "Встретили встречный поток.")
            )),
            new DragEventDefinition("E09", "Сломанный светофор", "Перекрёсток живёт по хаосу: светофор мигает вразнобой, а водители с разных направлений пытаются проскочить одновременно.", List.of(
                    new DragBranchDefinition("A", "Стоп-проверка", "Теряете секунды", 0.88, 0.88, 0.08, 0.85, 0.65, 0.96, 0.96, "Аккуратно проскочили.", "Небольшая задержка."),
                    new DragBranchDefinition("B", "На удачу", "Рандомный приоритет", 0.53, 0.53, 0.28, 1.00, 1.20, 1.20, 0.78, "Успели до потока!", "Подрезали вас на перекрёстке.")
            )),
            new DragEventDefinition("E12", "Горный серпантин", "Высокогорный серпантин с чередой слепых шпилек и перепадом высот: малейший просчёт траектории карается очень жёстко.", List.of(
                    new DragBranchDefinition("A", "Аккуратно", "Почти гарантированно", 0.88, 0.88, 0.08, 0.85, 0.65, 0.92, 0.92, "Выдержали траекторию.", "Немного потеряли темп."),
                    new DragBranchDefinition("B", "Ва-банк", "Максимальный риск", 0.42, 0.42, 0.45, 1.00, 1.20, 1.35, 0.70, "Рывок удался!", "Манёвр сорван из-за ошибки.")
            ))
    );
}
