package org.example;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class Main {

    public static void main(String[] args) {
        try (Scanner scanner = new Scanner(System.in)) {
            System.out.println("Выберите задачу для выполнения:");
            System.out.println("1: Анализ редакторов статьи Wikipedia");
            System.out.println("2: Анализ зарплат Java-разработчиков на HeadHunter");
            System.out.print("Введите номер задачи: ");

            int choice = scanner.nextInt();

            switch (choice) {
                case 1:
                    runWikipediaAnalysis();
                    break;
                case 2:
                    runHeadHunterAnalysis();
                    break;
                default:
                    System.out.println("Неверный выбор. Пожалуйста, запустите программу снова и выберите 1 или 2.");
            }
        } catch (Exception e) {
            System.err.println("Произошла критическая ошибка: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static void runWikipediaAnalysis() throws IOException {
        String wikipediaHistoryUrl = "https://ru.wikipedia.org/w/index.php?title=JSON&action=history&offset=&limit=500";
        System.out.println("\n--- Анализ редакторов статьи Wikipedia ---");
        System.out.println("Получение IP-адресов со страницы: " + wikipediaHistoryUrl);

        Set<String> ipAddresses = getUniqueIpAddresses(wikipediaHistoryUrl);
        System.out.println("Найдено " + ipAddresses.size() + " уникальных IP-адресов.");

        if (ipAddresses.isEmpty()) {
            System.out.println("Не найдено IP-адресов для анализа.");
            return;
        }

        Map<String, Integer> countryCounts = getCountryCountsFromIps(ipAddresses);
        System.out.println("\n--- Рейтинг стран по количеству редакторов ---");
        printSortedCountryRatings(countryCounts);
    }

    private static Set<String> getUniqueIpAddresses(String url) throws IOException {
        Set<String> ipAddresses = new HashSet<>();
        Document doc = Jsoup.connect(url).get();
        Elements ipLinks = doc.select("a.mw-anonuserlink");

        for (Element link : ipLinks) {
            ipAddresses.add(link.text());
        }
        return ipAddresses;
    }

    private static Map<String, Integer> getCountryCountsFromIps(Set<String> ipAddresses) {
        Map<String, Integer> countryCounts = new HashMap<>();
        int current = 0;
        for (String ip : ipAddresses) {
            System.out.printf("Обработка IP %d из %d: %s%n", ++current, ipAddresses.size(), ip);
            try {
                String apiUrl = "http://ipwhois.app/json/" + ip;
                String jsonResponse = getResponseFromUrl(new URL(apiUrl), "Mozilla/5.0");

                if (jsonResponse != null && !jsonResponse.isEmpty()) {
                    JSONObject jsonObject = new JSONObject(jsonResponse);
                    if (jsonObject.optBoolean("success", false)) {
                        String country = jsonObject.optString("country", "Не определена");
                        countryCounts.put(country, countryCounts.getOrDefault(country, 0) + 1);
                    } else {
                        countryCounts.put("Ошибка/Приватный IP", countryCounts.getOrDefault("Ошибка/Приватный IP", 0) + 1);
                    }
                }
                Thread.sleep(200);
            } catch (Exception e) {
                System.err.println("Не удалось обработать IP " + ip + ": " + e.getMessage());
                countryCounts.put("Ошибка обработки", countryCounts.getOrDefault("Ошибка обработки", 0) + 1);
            }
        }
        return countryCounts;
    }

    private static void printSortedCountryRatings(Map<String, Integer> countryCounts) {
        if (countryCounts.isEmpty()) {
            System.out.println("Данные для рейтинга отсутствуют.");
            return;
        }
        List<Map.Entry<String, Integer>> list = new ArrayList<>(countryCounts.entrySet());
        list.sort(Map.Entry.<String, Integer>comparingByValue().reversed());

        for (Map.Entry<String, Integer> entry : list) {
            System.out.printf("%-30s | %d %s%n", entry.getKey(), entry.getValue(), getEditorString(entry.getValue()));
        }
    }

    private static String getEditorString(int count) {
        if (count % 100 >= 11 && count % 100 <= 14) return "редакторов";
        switch (count % 10) {
            case 1: return "редактор";
            case 2: case 3: case 4: return "редактора";
            default: return "редакторов";
        }
    }

    private static final String CURRENCY_RATES_URL = "https://www.cbr-xml-daily.ru/daily_json.js";

    private static class RegionSalaryStats {
        String regionName;
        double totalSalarySum = 0;
        int vacancyCount = 0;
        public RegionSalaryStats(String name) { this.regionName = name; }
        public void addSalary(double salary) { this.totalSalarySum += salary; this.vacancyCount++; }
        public double getAverageSalary() { return vacancyCount > 0 ? totalSalarySum / vacancyCount : 0; }
    }

    public static void runHeadHunterAnalysis() throws IOException {
        System.out.println("\n--- Анализ зарплат для Java-разработчиков на HeadHunter ---");
        System.out.println("Получение курсов валют...");
        Map<String, Double> currencyRates = fetchCurrencyRates();
        System.out.println("Курсы валют успешно загружены.");

        Map<String, RegionSalaryStats> regionalStats = getRegionalSalaryStats(currencyRates);
        printHHResults(regionalStats);
    }

    private static Map<String, RegionSalaryStats> getRegionalSalaryStats(Map<String, Double> rates) throws IOException {
        Map<String, RegionSalaryStats> regionalStats = new HashMap<>();
        final int maxPages = 20;

        for (int page = 0; page < maxPages; page++) {
            System.out.printf("Обработка страницы %d...%n", page);
            URL url = new URL("https://api.hh.ru/vacancies?text=java&only_with_salary=true&per_page=100&page=" + page);

            String userAgent = "MyVacancyAnalyzer/1.0 (koliaagapov17@gmail.com)";
            String jsonResponse = getResponseFromUrl(url, userAgent);

            JSONObject responseJson = new JSONObject(jsonResponse);

            if (!responseJson.has("items")) {
                System.err.println("Ошибка при запросе к API HeadHunter. Сервер вернул следующий ответ:");

                System.err.println(responseJson.toString(2));
                break;
            }

            JSONArray vacancies = responseJson.getJSONArray("items");
            if (vacancies.isEmpty()) break;

            for (Object item : vacancies) {
                JSONObject vacancy = (JSONObject) item;
                JSONObject salaryInfo = vacancy.optJSONObject("salary");
                String regionName = vacancy.getJSONObject("area").getString("name");

                if (salaryInfo != null) {
                    double salaryInRub = calculateSalaryInRub(salaryInfo, rates);
                    if (salaryInRub > 0) {
                        regionalStats.computeIfAbsent(regionName, RegionSalaryStats::new).addSalary(salaryInRub);
                    }
                }
            }
        }
        return regionalStats;
    }

    private static double calculateSalaryInRub(JSONObject salaryInfo, Map<String, Double> rates) {
        double from = salaryInfo.optDouble("from", 0);
        double to = salaryInfo.optDouble("to", 0);
        String currency = salaryInfo.getString("currency").toUpperCase();
        double salary = from > 0 && to > 0 ? (from + to) / 2 : Math.max(from, to);
        if (salary == 0) return 0;
        if ("RUR".equals(currency)) currency = "RUB";
        return salary * rates.getOrDefault(currency, 1.0);
    }

    private static Map<String, Double> fetchCurrencyRates() throws IOException {
        Map<String, Double> rates = new HashMap<>();
        String jsonResponse = getResponseFromUrl(new URL(CURRENCY_RATES_URL), "Mozilla/5.0");
        JSONObject valute = new JSONObject(jsonResponse).getJSONObject("Valute");
        for (String key : valute.keySet()) {
            JSONObject currencyInfo = valute.getJSONObject(key);
            rates.put(key, currencyInfo.getDouble("Value") / currencyInfo.getDouble("Nominal"));
        }
        rates.put("RUB", 1.0);
        return rates;
    }

    private static void printHHResults(Map<String, RegionSalaryStats> regionalStats) {
        if(regionalStats.isEmpty()){
            System.out.println("\nНе удалось обработать вакансии. Проверьте сообщение об ошибке выше.");
            return;
        }
        System.out.println("\n--- Рейтинг регионов по средней зарплате (Java-разработчик) ---");
        System.out.printf("%-35s | %-20s | %s%n", "Регион", "Средняя зарплата", "Вакансий найдено");
        System.out.println(new String(new char[80]).replace("\0", "-"));

        List<RegionSalaryStats> sortedStats = new ArrayList<>(regionalStats.values());
        sortedStats.sort((a, b) -> Double.compare(b.getAverageSalary(), a.getAverageSalary()));

        for (RegionSalaryStats stats : sortedStats) {
            System.out.printf("%-35s | %,18.0f RUB | %d%n", stats.regionName, stats.getAverageSalary(), stats.vacancyCount);
        }
    }

    private static String getResponseFromUrl(URL url, String userAgent) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("User-Agent", userAgent);

        InputStreamReader streamReader;
        try {
            streamReader = new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            streamReader = new InputStreamReader(connection.getErrorStream(), StandardCharsets.UTF_8);
        }

        StringBuilder response = new StringBuilder();
        try (BufferedReader in = new BufferedReader(streamReader)) {
            String inputLine;
            while ((inputLine = in.readLine()) != null) {
                response.append(inputLine);
            }
        }
        return response.toString();
    }
}
