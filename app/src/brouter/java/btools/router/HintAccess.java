package btools.router;

import java.util.ArrayList;
import java.util.List;

/** Доступ к подсказкам поворотов BRouter (поля VoiceHint закрыты пакетом). Добавлено для «Всё в одном». */
public final class HintAccess {
  /** На каждую подсказку: {команда, индекс точки в треке, номер съезда с кольца, угол*10}. */
  public static List<int[]> hints(OsmTrack t) {
    List<int[]> out = new ArrayList<>();
    if (t == null || t.voiceHints == null) return out;
    for (VoiceHint h : t.voiceHints.list) {
      out.add(new int[]{h.cmd, h.indexInTrack, h.getExitNumber(), Float.isNaN(h.angle) || h.angle == Float.MAX_VALUE ? 0 : (int) (h.angle * 10)});
    }
    return out;
  }
}
