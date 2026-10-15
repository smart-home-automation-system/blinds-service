package cloud.cholewa.blinds.model.schedule;

/** When a schedule may fire on a day of its type: from one end to the other, local time of the house. */
public record ScheduleWindow(WindowEnd from, WindowEnd to) {
}
