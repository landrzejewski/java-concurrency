# Kolekcje i Strumienie Wielowątkowe w Javie

## Wprowadzenie

W środowiskach wielowątkowych standardowe kolekcje z pakietu `java.util` nie są bezpieczne. Java dostarcza specjalizowane kolekcje w pakiecie `java.util.concurrent`, które zapewniają thread-safety i wydajność w środowiskach współbieżnych.

## 1. Podstawowe Kolekcje Blokujące

### ConcurrentHashMap

**Teoria:**

- Alternatywa dla `HashMap` w środowiskach wielowątkowych
- Wykorzystuje segmentację (lock striping) - różne fragmenty mapy mogą być modyfikowane jednocześnie
- Nie blokuje całej mapy przy operacjach
- Iteratory są "weakly consistent" - nie rzucają `ConcurrentModificationException`
- **NIE** akceptuje null jako klucza ani wartości

**Przykład:**

```java
import java.util.concurrent.*;

public class ConcurrentHashMapExample {
    public static void main(String[] args) throws InterruptedException {
        ConcurrentHashMap<String, Integer> map = new ConcurrentHashMap<>();
        
        // Atomowa operacja - compute if absent
        map.computeIfAbsent("counter", k -> 0);
        
        // Wiele wątków inkrementuje licznik
        ExecutorService executor = Executors.newFixedThreadPool(10);
        for (int i = 0; i < 100; i++) {
            executor.submit(() -> 
                map.compute("counter", (k, v) -> v + 1)
            );
        }
        
        executor.shutdown();
        executor.awaitTermination(1, TimeUnit.SECONDS);
        
        System.out.println("Wartość końcowa: " + map.get("counter")); // 100
        
        // Operacje atomowe
        map.putIfAbsent("key", 1);
        map.merge("key", 5, Integer::sum); // 1 + 5 = 6
        System.out.println(map.get("key")); // 6
    }
}
```

### ConcurrentLinkedQueue

**Teoria:**

- Nieblokująca, oparta na algorytmie CAS (Compare-And-Swap)
- Idealny wybór gdy nie potrzebujemy blokowania
- FIFO (First In First Out)
- Nieograniczona pojemność

**Przykład:**

```java
import java.util.concurrent.ConcurrentLinkedQueue;

public class ConcurrentLinkedQueueExample {
    public static void main(String[] args) throws InterruptedException {
        ConcurrentLinkedQueue<String> queue = new ConcurrentLinkedQueue<>();
        
        // Producent
        Thread producer = new Thread(() -> {
            for (int i = 0; i < 5; i++) {
                queue.offer("Element-" + i);
                System.out.println("Dodano: Element-" + i);
            }
        });
        
        // Konsument
        Thread consumer = new Thread(() -> {
            for (int i = 0; i < 5; i++) {
                String element;
                while ((element = queue.poll()) == null) {
                    Thread.yield(); // Czekaj aktywnie
                }
                System.out.println("Pobrano: " + element);
            }
        });
        
        producer.start();
        consumer.start();
        producer.join();
        consumer.join();
    }
}
```

### ConcurrentLinkedDeque

**Teoria:**

- Dwukierunkowa kolejka (Double Ended Queue)
- Można dodawać/usuwać z obu końców
- Nieblokująca, oparta na CAS

**Przykład:**

```java
import java.util.concurrent.ConcurrentLinkedDeque;

public class ConcurrentLinkedDequeExample {
    public static void main(String[] args) {
        ConcurrentLinkedDeque<String> deque = new ConcurrentLinkedDeque<>();
        
        // Operacje na obu końcach
        deque.offerFirst("Pierwszy");
        deque.offerLast("Ostatni");
        deque.offerFirst("Nowy Pierwszy");
        
        System.out.println("Z przodu: " + deque.pollFirst());  // Nowy Pierwszy
        System.out.println("Z tyłu: " + deque.pollLast());      // Ostatni
        System.out.println("Pozostało: " + deque.pollFirst()); // Pierwszy
    }
}
```

## 2. Kolekcje Skalowalne i Automatycznie Sortujące

### ConcurrentSkipListMap

**Teoria:**

- Implementacja sortowanej mapy (jak `TreeMap` ale thread-safe)
- Oparta na strukturze Skip List (wielopoziomowa lista)
- Logarytmiczna złożoność operacji O(log n)
- Elementy zawsze posortowane według klucza
- Idealny do scenariuszy wymagających sortowania i współbieżności

**Przykład:**

```java
import java.util.concurrent.ConcurrentSkipListMap;

public class ConcurrentSkipListMapExample {
    public static void main(String[] args) {
        ConcurrentSkipListMap<Integer, String> map = new ConcurrentSkipListMap<>();
        
        // Dodawanie w losowej kolejności
        map.put(3, "Trzy");
        map.put(1, "Jeden");
        map.put(5, "Pięć");
        map.put(2, "Dwa");
        
        // Zawsze posortowane
        System.out.println("Wszystkie: " + map); // {1=Jeden, 2=Dwa, 3=Trzy, 5=Pięć}
        
        // Operacje zakresowe
        System.out.println("Od 2 do 4: " + map.subMap(2, 5)); // {2=Dwa, 3=Trzy}
        System.out.println("Pierwszy: " + map.firstEntry());   // 1=Jeden
        System.out.println("Ostatni: " + map.lastEntry());     // 5=Pięć
        
        // Operacje nawigacyjne
        System.out.println("Ceiling(4): " + map.ceilingEntry(4)); // 5=Pięć
        System.out.println("Floor(4): " + map.floorEntry(4));     // 3=Trzy
    }
}
```

### ConcurrentSkipListSet

**Teoria:**

- Sortowany set (jak `TreeSet` ale thread-safe)
- Wewnętrznie używa `ConcurrentSkipListMap`
- Nie akceptuje duplikatów
- Elementy zawsze posortowane

**Przykład:**

```java
import java.util.concurrent.ConcurrentSkipListSet;

public class ConcurrentSkipListSetExample {
    public static void main(String[] args) {
        ConcurrentSkipListSet<Integer> set = new ConcurrentSkipListSet<>();
        
        // Dodawanie w losowej kolejności
        set.add(50);
        set.add(10);
        set.add(30);
        set.add(20);
        
        System.out.println("Set: " + set); // [10, 20, 30, 50]
        
        // Operacje zakresowe
        System.out.println("Mniejsze niż 30: " + set.headSet(30));  // [10, 20]
        System.out.println("Większe niż 20: " + set.tailSet(20));   // [20, 30, 50]
        
        // Nawigacja
        System.out.println("Pierwszy: " + set.first());   // 10
        System.out.println("Ostatni: " + set.last());     // 50
        System.out.println("Wyższy niż 25: " + set.higher(25)); // 30
    }
}
```

---

## 3. Kolekcje Kopiujące Zawartość (Copy-On-Write)

### CopyOnWriteArrayList

**Teoria:**

- Przy każdej modyfikacji tworzy kopię całej tablicy
- Idealna gdy: odczyty >> zapisy
- Iteratory nigdy nie rzucają `ConcurrentModificationException`
- Iteratory działają na "migawce" z momentu utworzenia
- **Uwaga:** Drogie operacje zapisu!

**Przykład:**

```java
import java.util.Iterator;
import java.util.concurrent.CopyOnWriteArrayList;

public class CopyOnWriteArrayListExample {
    public static void main(String[] args) {
        CopyOnWriteArrayList<String> list = new CopyOnWriteArrayList<>();
        list.add("A");
        list.add("B");
        list.add("C");
        
        // Iterator działa na migawce
        Iterator<String> iterator = list.iterator();
        
        // Modyfikacja podczas iteracji - bezpieczna!
        list.add("D");
        list.remove("B");
        
        // Iterator widzi stary stan
        System.out.print("Iterator: ");
        while (iterator.hasNext()) {
            System.out.print(iterator.next() + " "); // A B C
        }
        
        System.out.println("\nAktualna lista: " + list); // [A, C, D]
        
        // Idealny dla słuchaczy zdarzeń
        CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
        listeners.add(() -> System.out.println("Listener 1"));
        listeners.add(() -> System.out.println("Listener 2"));
        
        // Bezpieczne wywołanie podczas potencjalnych modyfikacji
        listeners.forEach(Runnable::run);
    }
}
```

### CopyOnWriteArraySet

**Teoria:**

- Wewnętrznie używa `CopyOnWriteArrayList`
- Nie akceptuje duplikatów
- Te same charakterystyki co `CopyOnWriteArrayList`

**Przykład:**

```java
import java.util.concurrent.CopyOnWriteArraySet;

public class CopyOnWriteArraySetExample {
    public static void main(String[] args) {
        CopyOnWriteArraySet<String> set = new CopyOnWriteArraySet<>();
        
        set.add("Jabłko");
        set.add("Banan");
        set.add("Jabłko"); // Duplikat - nie zostanie dodany
        
        System.out.println("Set: " + set); // [Jabłko, Banan]
        
        // Bezpieczna iteracja z modyfikacją
        for (String fruit : set) {
            System.out.println(fruit);
            if (fruit.equals("Banan")) {
                set.add("Gruszka"); // Bezpieczne
            }
        }
        
        System.out.println("Po iteracji: " + set); // [Jabłko, Banan, Gruszka]
    }
}
```

## 4. Kolejki Blokujące

### Porównanie Interfejsów

|Interfejs|Charakterystyka|Główne metody|
|---|---|---|
|**BlockingQueue**|Kolejka z blokowaniem przy pełnej/pustej|`put()`, `take()`|
|**TransferQueue**|Rozszerzenie BlockingQueue, producer czeka na consumer|`transfer()`, `tryTransfer()`|

### BlockingQueue - Implementacje

#### ArrayBlockingQueue

**Teoria:**

- Ograniczona pojemność (bounded)
- Oparta na tablicy
- FIFO
- Fairness opcjonalny (parametr konstruktora)

**Przykład:**

```java
import java.util.concurrent.*;

public class ArrayBlockingQueueExample {
    public static void main(String[] args) throws InterruptedException {
        BlockingQueue<String> queue = new ArrayBlockingQueue<>(3);
        
        // Producent
        new Thread(() -> {
            try {
                queue.put("Element-1");
                System.out.println("Dodano Element-1");
                queue.put("Element-2");
                System.out.println("Dodano Element-2");
                queue.put("Element-3");
                System.out.println("Dodano Element-3");
                queue.put("Element-4"); // Zablokuje się - kolejka pełna
                System.out.println("Dodano Element-4");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
        
        Thread.sleep(1000); // Kolejka pełna
        
        // Konsument
        System.out.println("Pobrano: " + queue.take()); // Odblokuje producenta
        System.out.println("Pobrano: " + queue.take());
        
        Thread.sleep(500);
    }
}
```

#### LinkedBlockingQueue

**Teoria:**

- Opcjonalnie ograniczona pojemność
- Oparta na połączonej liście
- Wyższa przepustowość niż `ArrayBlockingQueue`

**Przykład:**

```java
import java.util.concurrent.*;

public class LinkedBlockingQueueExample {
    public static void main(String[] args) throws InterruptedException {
        // Bez limitu lub z limitem
        BlockingQueue<Integer> queue = new LinkedBlockingQueue<>(5);
        
        ExecutorService executor = Executors.newFixedThreadPool(2);
        
        // Producent
        executor.submit(() -> {
            for (int i = 1; i <= 10; i++) {
                try {
                    queue.put(i);
                    System.out.println("Wyprodukowano: " + i);
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        
        // Konsument
        executor.submit(() -> {
            for (int i = 1; i <= 10; i++) {
                try {
                    Integer value = queue.take();
                    System.out.println("  Skonsumowano: " + value);
                    Thread.sleep(200); // Wolniejszy konsument
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
    }
}
```

#### PriorityBlockingQueue

**Teoria:**

- Nieograniczona kolejka priorytetowa
- Elementy pobierane według priorytetu (natural ordering lub Comparator)
- **Uwaga:** `take()` blokuje tylko gdy pusta, nie ma limitu górnego

**Przykład:**

```java
import java.util.concurrent.*;

public class PriorityBlockingQueueExample {
    static class Task implements Comparable<Task> {
        String name;
        int priority;
        
        Task(String name, int priority) {
            this.name = name;
            this.priority = priority;
        }
        
        @Override
        public int compareTo(Task other) {
            return Integer.compare(other.priority, this.priority); // Wyższy priorytet = pierwszy
        }
        
        @Override
        public String toString() {
            return name + "(p=" + priority + ")";
        }
    }
    
    public static void main(String[] args) throws InterruptedException {
        BlockingQueue<Task> queue = new PriorityBlockingQueue<>();
        
        queue.put(new Task("Zadanie-1", 5));
        queue.put(new Task("Zadanie-2", 10)); // Najwyższy priorytet
        queue.put(new Task("Zadanie-3", 3));
        queue.put(new Task("Zadanie-4", 7));
        
        // Pobieranie według priorytetu
        System.out.println(queue.take()); // Zadanie-2(p=10)
        System.out.println(queue.take()); // Zadanie-4(p=7)
        System.out.println(queue.take()); // Zadanie-1(p=5)
        System.out.println(queue.take()); // Zadanie-3(p=3)
    }
}
```

#### SynchronousQueue

**Teoria:**

- Pojemność = 0 (brak bufora)
- Producer musi czekać na consumer i vice versa
- Idealna do handoff (przekazania) między wątkami

**Przykład:**

```java
import java.util.concurrent.*;

public class SynchronousQueueExample {
    public static void main(String[] args) throws InterruptedException {
        SynchronousQueue<String> queue = new SynchronousQueue<>();
        
        // Producer
        new Thread(() -> {
            try {
                System.out.println("Producer: próbuję wysłać...");
                queue.put("Wiadomość"); // Zablokuje się do momentu odbioru
                System.out.println("Producer: wysłano!");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
        
        Thread.sleep(2000); // Producer czeka
        
        // Consumer
        System.out.println("Consumer: pobieram...");
        String msg = queue.take();
        System.out.println("Consumer: otrzymano: " + msg);
    }
}
```

#### DelayQueue

**Teoria:**

- Elementy pobierane dopiero po upływie określonego czasu
- Elementy muszą implementować `Delayed`
- Idealna do zadań zaplanowanych

**Przykład:**

```java
import java.util.concurrent.*;

public class DelayQueueExample {
    static class DelayedTask implements Delayed {
        String name;
        long executeAt;
        
        DelayedTask(String name, long delayMs) {
            this.name = name;
            this.executeAt = System.currentTimeMillis() + delayMs;
        }
        
        @Override
        public long getDelay(TimeUnit unit) {
            long diff = executeAt - System.currentTimeMillis();
            return unit.convert(diff, TimeUnit.MILLISECONDS);
        }
        
        @Override
        public int compareTo(Delayed o) {
            return Long.compare(this.executeAt, ((DelayedTask)o).executeAt);
        }
        
        @Override
        public String toString() {
            return name;
        }
    }
    
    public static void main(String[] args) throws InterruptedException {
        DelayQueue<DelayedTask> queue = new DelayQueue<>();
        
        queue.put(new DelayedTask("Zadanie-3s", 3000));
        queue.put(new DelayedTask("Zadanie-1s", 1000));
        queue.put(new DelayedTask("Zadanie-2s", 2000));
        
        System.out.println("Start: " + System.currentTimeMillis());
        
        for (int i = 0; i < 3; i++) {
            DelayedTask task = queue.take(); // Blokuje do momentu gotowości
            System.out.println("Wykonano: " + task + " w " + System.currentTimeMillis());
        }
    }
}
```

### LinkedTransferQueue

**Teoria:**

- Implementuje `TransferQueue`
- Producer może czekać aż consumer pobierze element (`transfer()`)
- Lub wysłać bez czekania (`put()`)
- Hybrydowa kolejka: może działać synchronicznie lub asynchronicznie

**Przykład:**

```java
import java.util.concurrent.*;

public class LinkedTransferQueueExample {
    public static void main(String[] args) throws InterruptedException {
        TransferQueue<String> queue = new LinkedTransferQueue<>();
        
        // Producer z transfer (czeka na consumer)
        new Thread(() -> {
            try {
                System.out.println("Producer: transferuję element...");
                queue.transfer("Element-Direct"); // Czeka aż consumer pobierze
                System.out.println("Producer: element odebrany przez consumer!");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
        
        Thread.sleep(2000); // Producer czeka
        
        // Consumer
        System.out.println("Consumer: pobieram element...");
        String element = queue.take();
        System.out.println("Consumer: otrzymano: " + element);
        
        // Zwykłe put - nie czeka
        queue.put("Element-Async");
        System.out.println("Producer: wysłano asynchronicznie");
        System.out.println("W kolejce: " + queue.take());
    }
}
```

## 5. Wrappery Synchronizowane

**Teoria:**

- `Collections.synchronizedXxx()` tworzy wrapper z synchronized
- **Każda** operacja jest synchronizowana
- **Ważne:** Iteracja wymaga ręcznej synchronizacji!
- Mniejsza wydajność niż dedykowane kolekcje concurrent

**Przykład:**

```java
import java.util.*;

public class SynchronizedCollectionsExample {
    public static void main(String[] args) {
        // Wrapper na ArrayList
        List<String> syncList = Collections.synchronizedList(new ArrayList<>());
        
        syncList.add("A");
        syncList.add("B");
        syncList.add("C");
        
        // POPRAWNA iteracja - wymagana ręczna synchronizacja
        synchronized (syncList) {
            for (String s : syncList) {
                System.out.println(s);
            }
        }
        
        // BŁĘDNA iteracja - brak synchronizacji
        // for (String s : syncList) { // Może rzucić ConcurrentModificationException
        //     System.out.println(s);
        // }
        
        // Podobnie dla innych kolekcji
        Set<Integer> syncSet = Collections.synchronizedSet(new HashSet<>());
        Map<String, Integer> syncMap = Collections.synchronizedMap(new HashMap<>());
        
        syncMap.put("klucz", 1);
        
        // Operacje atomowe NIE SĄ wspierane
        // Musisz ręcznie synchronizować
        synchronized (syncMap) {
            Integer value = syncMap.get("klucz");
            syncMap.put("klucz", value + 1); // Operacja check-then-act
        }
        
        System.out.println("Wartość: " + syncMap.get("klucz"));
    }
}
```

**Porównanie: Synchronized Wrappers vs Concurrent Collections**

|Aspekt|Synchronized Wrappers|Concurrent Collections|
|---|---|---|
|Wydajność|Niska (cała kolekcja blokowana)|Wysoka (fine-grained locking)|
|Iteracja|Wymaga ręcznej synchronizacji|Bezpieczna "out of the box"|
|Operacje atomowe|Brak|Tak (`compute`, `merge`, etc.)|
|Kiedy używać|Legacy kod, proste przypadki|Nowe aplikacje, wysoka współbieżność|

## 6. Strumienie Wielowątkowe

### Strumienie Równoległe (Parallel Streams)

**Teoria:**

- `.parallelStream()` lub `.parallel()` na zwykłym strumieniu
- Wykorzystują ForkJoinPool.commonPool()
- Dzielą dane na segmenty i przetwarzają równolegle
- **Idealne dla:** operacji CPU-bound, duże zbiory danych, niezależne operacje

**Przykład:**

```java
import java.util.*;
import java.util.stream.*;

public class ParallelStreamExample {
    public static void main(String[] args) {
        List<Integer> numbers = IntStream.rangeClosed(1, 1000)
                                         .boxed()
                                         .collect(Collectors.toList());
        
        // Strumień sekwencyjny
        long startSeq = System.currentTimeMillis();
        long sumSeq = numbers.stream()
                             .mapToLong(i -> computeHeavy(i))
                             .sum();
        long timeSeq = System.currentTimeMillis() - startSeq;
        
        // Strumień równoległy
        long startPar = System.currentTimeMillis();
        long sumPar = numbers.parallelStream()
                             .mapToLong(i -> computeHeavy(i))
                             .sum();
        long timePar = System.currentTimeMillis() - startPar;
        
        System.out.println("Sekwencyjnie: " + timeSeq + "ms, suma: " + sumSeq);
        System.out.println("Równolegle: " + timePar + "ms, suma: " + sumPar);
        
        // Przykład z collect
        List<String> result = numbers.parallelStream()
                                     .filter(n -> n % 2 == 0)
                                     .map(n -> "Liczba-" + n)
                                     .collect(Collectors.toList());
        
        System.out.println("Przetworzono " + result.size() + " elementów");
    }
    
    static long computeHeavy(int n) {
        // Symulacja ciężkiej operacji
        long sum = 0;
        for (int i = 0; i < 1000; i++) {
            sum += Math.sqrt(n * i);
        }
        return sum;
    }
}
```

## 7. Zagrożenia Płynące z Naiwnego Używania Strumieni Wielowątkowych

### Problem 1: Współdzielony Stan Mutowalny

**Zły przykład:**

```java
import java.util.*;
import java.util.stream.*;

public class ParallelStreamProblem1 {
    public static void main(String[] args) {
        List<Integer> numbers = IntStream.rangeClosed(1, 1000).boxed().toList();
        
        // BŁĄD: ArrayList nie jest thread-safe!
        List<Integer> results = new ArrayList<>();
        numbers.parallelStream()
               .filter(n -> n % 2 == 0)
               .forEach(results::add); // Race condition!
        
        System.out.println("Rozmiar: " + results.size()); // Nie zawsze 500!
        
        // POPRAWNE rozwiązanie 1: Użyj collect
        List<Integer> correctResults = numbers.parallelStream()
                                              .filter(n -> n % 2 == 0)
                                              .collect(Collectors.toList());
        
        System.out.println("Poprawny rozmiar: " + correctResults.size()); // Zawsze 500
        
        // POPRAWNE rozwiązanie 2: Thread-safe kolekcja
        List<Integer> syncResults = Collections.synchronizedList(new ArrayList<>());
        numbers.parallelStream()
               .filter(n -> n % 2 == 0)
               .forEach(syncResults::add);
        
        System.out.println("Sync rozmiar: " + syncResults.size()); // Zawsze 500
    }
}
```

### Problem 2: Operacje Zależne od Kolejności

**Zły przykład:**

```java
import java.util.stream.*;

public class ParallelStreamProblem2 {
    public static void main(String[] args) {
        // PROBLEM: findFirst() w strumieniu równoległym traci sens
        String first = IntStream.rangeClosed(1, 100)
                                .parallel()
                                .mapToObj(String::valueOf)
                                .findFirst()
                                .orElse(null);
        
        System.out.println("Pierwszy: " + first); // Zawsze "1" ale z narzutem
        
        // LEPIEJ: Użyj sekwencyjnego dla operacji zależnych od kolejności
        String firstSeq = IntStream.rangeClosed(1, 100)
                                   .mapToObj(String::valueOf)
                                   .findFirst()
                                   .orElse(null);
        
        // LUB: Użyj findAny() jeśli kolejność nie ma znaczenia
        String any = IntStream.rangeClosed(1, 100)
                              .parallel()
                              .mapToObj(String::valueOf)
                              .findAny() // Wydajniejsze w strumieniu równoległym
                              .orElse(null);
        
        System.out.println("Dowolny: " + any);
        
        // PROBLEM: forEach nie gwarantuje kolejności
        System.out.print("Parallel forEach: ");
        IntStream.rangeClosed(1, 10)
                 .parallel()
                 .forEach(n -> System.out.print(n + " ")); // Losowa kolejność!
        
        System.out.print("\nSequential forEach: ");
        IntStream.rangeClosed(1, 10)
                 .forEach(n -> System.out.print(n + " ")); // Zachowana kolejność
        
        // ROZWIĄZANIE: forEachOrdered
        System.out.print("\nforEachOrdered: ");
        IntStream.rangeClosed(1, 10)
                 .parallel()
                 .forEachOrdered(n -> System.out.print(n + " ")); // Zachowana kolejność
        
        System.out.println();
    }
}
```

### Problem 3: Blokowanie I/O w Strumieniach Równoległych

**Zły przykład:**

```java
import java.util.*;
import java.util.stream.*;

public class ParallelStreamProblem3 {
    public static void main(String[] args) {
        List<String> urls = Arrays.asList(
            "http://example1.com",
            "http://example2.com",
            "http://example3.com",
            "http://example4.com"
        );
        
        // BŁĄD: Blokujące operacje I/O w ForkJoinPool.commonPool()
        // Zablokują wątki puli i spowolnią całą aplikację!
        List<String> contents = urls.parallelStream()
                                    .map(url -> downloadContent(url)) // Blokujące I/O!
                                    .collect(Collectors.toList());
        
        // LEPSZE rozwiązanie: Użyj dedykowanego ExecutorService
        // (poza strumieniem równoległym)
        System.out.println("Użyj CompletableFuture z własnym ExecutorService dla I/O");
    }
    
    static String downloadContent(String url) {
        try {
            Thread.sleep(1000); // Symulacja I/O
            return "Content from " + url;
        } catch (InterruptedException e) {
            return "";
        }
    }
}
```

### Problem 4: Rozmiar Common Pool

**Przykład:**

```java
import java.util.concurrent.ForkJoinPool;
import java.util.stream.IntStream;

public class ParallelStreamProblem4 {
    public static void main(String[] args) {
        // Common pool ma rozmiar = liczba rdzeni - 1
        int parallelism = ForkJoinPool.commonPool().getParallelism();
        System.out.println("Common pool parallelism: " + parallelism);
        
        // PROBLEM: Wszystkie równoległe strumienie współdzielą tę samą pulę
        // Może prowadzić do głodzenia wątków (thread starvation)
        
        // ROZWIĄZANIE: Własny ForkJoinPool dla krytycznych operacji
        ForkJoinPool customPool = new ForkJoinPool(10);
        
        try {
            long sum = customPool.submit(() ->
                IntStream.rangeClosed(1, 100)
                         .parallel()
                         .sum()
            ).get();
            
            System.out.println("Suma: " + sum);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            customPool.shutdown();
        }
    }
}
```

### Problem 5: Overhead dla Małych Kolekcji

**Przykład:**

```java
import java.util.stream.IntStream;

public class ParallelStreamProblem5 {
    public static void main(String[] args) {
        // Mała kolekcja - overhead większy niż zysk
        long startPar = System.nanoTime();
        long sumPar = IntStream.rangeClosed(1, 100)
                               .parallel()
                               .sum();
        long timePar = System.nanoTime() - startPar;
        
        long startSeq = System.nanoTime();
        long sumSeq = IntStream.rangeClosed(1, 100)
                               .sum();
        long timeSeq = System.nanoTime() - startSeq;
        
        System.out.println("Parallel: " + timePar + "ns");
        System.out.println("Sequential: " + timeSeq + "ns");
        // Sequential często szybsze dla małych kolekcji!
        
        // ZASADA: Użyj parallel tylko gdy:
        // 1. Duży zbiór danych (>10000 elementów)
        // 2. Operacje CPU-bound
        // 3. Niezależne operacje (bez współdzielonego stanu)
    }
}
```
