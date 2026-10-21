import java.util.*;
import java.util.concurrent.TimeUnit;

public class Main {

    enum VehicleType { BIKE, CAR, TRUCK }
    enum SpotStatus { AVAILABLE, OCCUPIED }
    enum PaymentStatus { NOT_PAID, PAID, FAILED }

    static class Vehicle {
        private final String vehicleNumber;
        private final VehicleType vehicleType;

        Vehicle(String vehicleNumber, VehicleType vehicleType) {
            if (vehicleNumber == null || vehicleNumber.trim().isEmpty()) {
                throw new IllegalArgumentException("Vehicle number cannot be empty.");
            }
            this.vehicleNumber = vehicleNumber.trim().toUpperCase();
            this.vehicleType = Objects.requireNonNull(vehicleType);
        }

        String getVehicleNumber() { return vehicleNumber; }
        VehicleType getVehicleType() { return vehicleType; }
    }

    static class ParkingSpot {
        private final String spotId;
        private final int floorNumber;
        private final VehicleType vehicleType;
        private SpotStatus status = SpotStatus.AVAILABLE;

        ParkingSpot(String spotId, int floorNumber, VehicleType vehicleType) {
            this.spotId = spotId;
            this.floorNumber = floorNumber;
            this.vehicleType = vehicleType;
        }

        String getSpotId() { return spotId; }
        int getFloorNumber() { return floorNumber; }
        VehicleType getVehicleType() { return vehicleType; }
        boolean isAvailable() { return status == SpotStatus.AVAILABLE; }

        void occupy() {
            if (!isAvailable()) {
                throw new IllegalStateException("Spot is already occupied: " + spotId);
            }
            status = SpotStatus.OCCUPIED;
        }

        void release() {
            if (isAvailable()) {
                throw new IllegalStateException("Spot is already available: " + spotId);
            }
            status = SpotStatus.AVAILABLE;
        }
    }

    static class ParkingFloor {
        private final int floorNumber;
        private final List<ParkingSpot> spots = new ArrayList<>();

        ParkingFloor(int floorNumber) {
            this.floorNumber = floorNumber;
        }

        void addSpot(ParkingSpot spot) {
            if (spot.getFloorNumber() != floorNumber) {
                throw new IllegalArgumentException("Spot belongs to a different floor.");
            }
            spots.add(spot);
        }

        List<ParkingSpot> getAvailableSpots(VehicleType type) {
            List<ParkingSpot> result = new ArrayList<>();
            for (ParkingSpot spot : spots) {
                if (spot.getVehicleType() == type && spot.isAvailable()) {
                    result.add(spot);
                }
            }
            return result;
        }

        int getAvailableSpotsCount(VehicleType type) {
            return getAvailableSpots(type).size();
        }
    }

    static class ParkingTicket {
        private final String ticketId;
        private final Vehicle vehicle;
        private final String parkingSpotId;
        private final long entryTimestamp;
        private Long exitTimestamp;
        private PaymentStatus paymentStatus = PaymentStatus.NOT_PAID;

        ParkingTicket(String ticketId, Vehicle vehicle, String parkingSpotId, long entryTimestamp) {
            this.ticketId = ticketId;
            this.vehicle = vehicle;
            this.parkingSpotId = parkingSpotId;
            this.entryTimestamp = entryTimestamp;
        }

        String getTicketId() { return ticketId; }
        Vehicle getVehicle() { return vehicle; }
        String getParkingSpotId() { return parkingSpotId; }
        long getEntryTimestamp() { return entryTimestamp; }
        Long getExitTimestamp() { return exitTimestamp; }
        PaymentStatus getPaymentStatus() { return paymentStatus; }

        void markExited(long timestamp) {
            if (exitTimestamp != null) {
                throw new IllegalStateException("Ticket has already been checked out.");
            }
            exitTimestamp = timestamp;
        }

        void markPaid() {
            paymentStatus = PaymentStatus.PAID;
        }

        long getParkingDurationMillis() {
            long end = exitTimestamp == null ? System.currentTimeMillis() : exitTimestamp;
            return Math.max(0L, end - entryTimestamp);
        }
    }

    static class TicketService {
        private final Map<String, ParkingTicket> ticketsById = new HashMap<>();
        private final Map<String, String> activeTicketByVehicle = new HashMap<>();

        synchronized ParkingTicket createTicket(Vehicle vehicle, ParkingSpot spot) {
            String vehicleNumber = vehicle.getVehicleNumber();
            if (activeTicketByVehicle.containsKey(vehicleNumber)) {
                throw new IllegalStateException("Vehicle is already parked: " + vehicleNumber);
            }

            ParkingTicket ticket = new ParkingTicket(
                UUID.randomUUID().toString(), vehicle, spot.getSpotId(), System.currentTimeMillis()
            );
            ticketsById.put(ticket.getTicketId(), ticket);
            activeTicketByVehicle.put(vehicleNumber, ticket.getTicketId());
            return ticket;
        }

        synchronized ParkingTicket getTicketById(String ticketId) {
            ParkingTicket ticket = ticketsById.get(ticketId);
            if (ticket == null) {
                throw new IllegalArgumentException("Ticket ID does not exist.");
            }
            return ticket;
        }

        synchronized void completeTicket(ParkingTicket ticket) {
            activeTicketByVehicle.remove(ticket.getVehicle().getVehicleNumber());
        }
    }

    static class ParkingLotRepository {
        private final List<ParkingFloor> floors = new ArrayList<>();

        synchronized void addFloor(ParkingFloor floor) {
            floors.add(floor);
        }

        synchronized List<ParkingFloor> getFloors() {
            return new ArrayList<>(floors);
        }

        synchronized List<ParkingSpot> findAvailableSpots(VehicleType type) {
            List<ParkingSpot> result = new ArrayList<>();
            for (ParkingFloor floor : floors) {
                result.addAll(floor.getAvailableSpots(type));
            }
            return result;
        }

        synchronized ParkingSpot findSpotById(String spotId) {
            for (ParkingFloor floor : floors) {
                for (VehicleType type : VehicleType.values()) {
                    for (ParkingSpot spot : floor.getAvailableSpots(type)) {
                        if (spot.getSpotId().equals(spotId)) return spot;
                    }
                }
                // Also search occupied spots indirectly is not exposed by floor;
                // exit uses the spot map maintained by ParkingLotManager instead.
            }
            return null;
        }
    }

    static class FeeCalculator {
        private final Map<VehicleType, long[]> rates = new EnumMap<>(VehicleType.class);

        FeeCalculator() {
            rates.put(VehicleType.BIKE, new long[]{20, 10});
            rates.put(VehicleType.CAR, new long[]{50, 30});
            rates.put(VehicleType.TRUCK, new long[]{100, 60});
        }

        long calculateFee(VehicleType type, long durationMillis) {
            long firstHourAndParts = Math.max(1L,
                (durationMillis + TimeUnit.HOURS.toMillis(1) - 1) / TimeUnit.HOURS.toMillis(1));
            long[] rate = rates.get(type);
            return rate[0] + Math.max(0L, firstHourAndParts - 1L) * rate[1];
        }
    }

    static class ParkingLotManager {
        private final ParkingLotRepository repository;
        private final TicketService ticketService;
        private final FeeCalculator feeCalculator;
        private final Map<String, ParkingSpot> spotsById = new HashMap<>();

        ParkingLotManager(ParkingLotRepository repository, TicketService ticketService,
                          FeeCalculator feeCalculator) {
            this.repository = repository;
            this.ticketService = ticketService;
            this.feeCalculator = feeCalculator;
        }

        synchronized void registerSpot(ParkingSpot spot) {
            if (spotsById.putIfAbsent(spot.getSpotId(), spot) != null) {
                throw new IllegalArgumentException("Duplicate spot ID: " + spot.getSpotId());
            }
        }

        synchronized ParkingTicket parkVehicle(Vehicle vehicle) {
            // Check duplicate before reserving a spot.
            for (ParkingSpot spot : repository.findAvailableSpots(vehicle.getVehicleType())) {
                // TicketService also guards duplicate active vehicles.
                spot.occupy();
                try {
                    return ticketService.createTicket(vehicle, spot);
                } catch (RuntimeException ex) {
                    spot.release();
                    throw ex;
                }
            }
            throw new IllegalStateException("No available spot for " + vehicle.getVehicleType());
        }

        synchronized long exitAndPay(String ticketId, boolean paymentSucceeds) {
            ParkingTicket ticket = ticketService.getTicketById(ticketId);
            if (ticket.getPaymentStatus() == PaymentStatus.PAID) {
                throw new IllegalStateException("Ticket has already been paid.");
            }

            long exitTime = System.currentTimeMillis();
            long durationMillis = Math.max(0L, exitTime - ticket.getEntryTimestamp());
            long fee = feeCalculator.calculateFee(
                ticket.getVehicle().getVehicleType(), durationMillis
            );

            if (!paymentSucceeds) {
                // Do not mark the ticket exited or release the spot; payment can be retried.
                throw new IllegalStateException("Payment failed. Spot remains occupied. Amount due: Rs. " + fee);
            }

            ticket.markExited(exitTime);
            ticket.markPaid();
            ParkingSpot spot = spotsById.get(ticket.getParkingSpotId());
            if (spot == null) {
                throw new IllegalStateException("Parking spot not found: " + ticket.getParkingSpotId());
            }
            spot.release();
            ticketService.completeTicket(ticket);
            System.out.println("Payment successful. Fee: Rs. " + fee);
            return fee;
        }

        void printAvailability() {
            for (ParkingFloor floor : repository.getFloors()) {
                System.out.println("Floor " + floor.floorNumber
                    + " | Bike: " + floor.getAvailableSpotsCount(VehicleType.BIKE)
                    + " | Car: " + floor.getAvailableSpotsCount(VehicleType.CAR)
                    + " | Truck: " + floor.getAvailableSpotsCount(VehicleType.TRUCK));
            }
        }
    }

    public static void main(String[] args) throws InterruptedException {
        ParkingLotRepository repository = new ParkingLotRepository();
        TicketService ticketService = new TicketService();
        FeeCalculator feeCalculator = new FeeCalculator();
        ParkingLotManager manager = new ParkingLotManager(repository, ticketService, feeCalculator);

        ParkingFloor floor0 = new ParkingFloor(0);
        ParkingFloor floor1 = new ParkingFloor(1);

        ParkingSpot bike0 = new ParkingSpot("B-0-1", 0, VehicleType.BIKE);
        ParkingSpot car0 = new ParkingSpot("C-0-1", 0, VehicleType.CAR);
        ParkingSpot car1 = new ParkingSpot("C-1-1", 1, VehicleType.CAR);
        ParkingSpot truck1 = new ParkingSpot("T-1-1", 1, VehicleType.TRUCK);

        floor0.addSpot(bike0);
        floor0.addSpot(car0);
        floor1.addSpot(car1);
        floor1.addSpot(truck1);

        repository.addFloor(floor0);
        repository.addFloor(floor1);

        manager.registerSpot(bike0);
        manager.registerSpot(car0);
        manager.registerSpot(car1);
        manager.registerSpot(truck1);

        System.out.println("Initial availability:");
        manager.printAvailability();

        Vehicle car = new Vehicle("KA-01-AB-1234", VehicleType.CAR);
        ParkingTicket ticket = manager.parkVehicle(car);
        System.out.println("\nParked car " + car.getVehicleNumber());
        System.out.println("Ticket: " + ticket.getTicketId());
        System.out.println("Spot: " + ticket.getParkingSpotId());

        System.out.println("\nAvailability after entry:");
        manager.printAvailability();

        // Demo-only pause so the exit fee is based on a real elapsed duration.
        Thread.sleep(1200);
        manager.exitAndPay(ticket.getTicketId(), true);

        System.out.println("\nAvailability after exit/payment:");
        manager.printAvailability();

        // Example of the fee policy: car parked for 61 minutes costs Rs. 80.
        long sixtyOneMinutes = TimeUnit.MINUTES.toMillis(61);
        System.out.println("\nExample fee for car parked 61 minutes: Rs. "
            + feeCalculator.calculateFee(VehicleType.CAR, sixtyOneMinutes));
    }
}
