package steamgiftsautomanager;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class SteamgiftsHttpClient {
    private static final String BASE_URL = "https://www.steamgifts.com";
    private static final String GIVEAWAY_SEARCH_URL = BASE_URL + "/giveaways/search?page=";
    private static final String AJAX_REQUEST_URL = BASE_URL + "/ajax.php";
    private static final String ENTERED_GIVEAWAYS_URL = BASE_URL + "/giveaways/entered";
    private static final String ENTERED_GIVEAWAYS_SEARCH_URL = ENTERED_GIVEAWAYS_URL + "/search?page=";
    private static final String INNER_GIVEAWAY_WRAP_CLASS = ".giveaway__row-inner-wrap";
    private static final String GIVEAWAY_HEADING_NAME_CLASS = ".giveaway__heading__name";
    private static final String GIVEAWAY_THUMBNAIL_CLASS = ".giveaway_image_thumbnail";
    private static final String GIVEAWAY_THUMBNAIL_MISSING_CLASS = ".giveaway_image_thumbnail_missing";
    private static final String GIVEAWAY_MISC_CLASS = ".giveaway__heading__thin";
    private static final String NAV_POINTS_CLASS = ".nav__points";
    private static final String TABLE_ROW_INNER_WRAP_CLASS = ".table__row-inner-wrap";
    private static final String TABLE_COLUMN_SECONDARY_LINK_CLASS = ".table__column__secondary-link";
    private static final String TABLE_COLUMN_HEADING_CLASS = ".table__column__heading";
    private static final String NOT_NUMBER_REGEX = "[^0-9]";
    private static final String[] SUCCESS_KEYWORDS = {"success", "entry_count", "points"};
    private final RequestsFileContent requestsFileContent;

    private boolean hasNoSession() {
        Document document = getDocumentFromUrl(BASE_URL);
        if (document == null) return false;
        return document.toString().contains("Sign in through STEAM");
    }

    public SteamgiftsHttpClient(RequestsFileContent requestsFileContent) {
        this.requestsFileContent = requestsFileContent;
        if (hasNoSession()) throw new RuntimeException("No session associated with the provided cookie found");
    }

    public Giveaway[] scrapeAvailableGiveaways() {
        HashMap<String, Giveaway> giveaways = new HashMap<>();
        int pageNumber = 1;
        AtomicInteger scrappedPages = new AtomicInteger(0);
        boolean hasMorePages = true;
        int requestBatch = 10;
        Instant startTime = Instant.now();

        ExecutorService threadPool = Executors.newCachedThreadPool();
        List<CompletableFuture<Giveaway[]>> futures = new ArrayList<>();
        do {
            for (int i = 0; i < requestBatch; i++) {
                int page = pageNumber;
                futures.add(CompletableFuture.supplyAsync(() -> {
                    Document document = getDocumentFromUrl(GIVEAWAY_SEARCH_URL + page);

                    if (document == null || document.toString().contains("No results were found.")) {
                        return null;
                    }

                    Elements gameElements = document.select(INNER_GIVEAWAY_WRAP_CLASS);
                    ArrayList<Giveaway> giveawayList = new ArrayList<>();
                    for (Element element : gameElements) {
                        Giveaway giveaway = getGiveawayFromElement(element);
                        if (giveaway != null) {
                            giveawayList.add(giveaway);
                        }
                    }

                    scrappedPages.getAndIncrement();

                    return giveawayList.toArray(new Giveaway[0]);
                }, threadPool));
                pageNumber++;
            }

            for (CompletableFuture<Giveaway[]> future : futures) {
                try {
                    Giveaway[] giveawayList = future.get();
                    if (giveawayList == null) {
                        hasMorePages = false;
                    } else {
                        for (Giveaway giveaway : giveawayList) {
                            giveaways.put(giveaway.getRelativeUrl(), giveaway);
                        }
                    }
                } catch (ExecutionException | InterruptedException e) {
                    e.printStackTrace();
                    Thread.currentThread().interrupt();
                }
            }

            Utils.printScrapedGiveaways(scrappedPages.get(), giveaways.size(), Duration.between(startTime, Instant.now()).toMillis());
        } while (hasMorePages);
        threadPool.shutdownNow();

        System.out.println();

        return giveaways.values().toArray(new Giveaway[0]);
    }

    private Giveaway getGiveawayFromElement(Element element) {
        Element nameElement = element.select(GIVEAWAY_HEADING_NAME_CLASS).first();
        if (nameElement == null) return null;
        String title = nameElement.text();

        String relativeUrl;
        Elements elements = element.select(GIVEAWAY_THUMBNAIL_CLASS);
        if (elements.hasAttr("href")) {
            relativeUrl = elements.attr("href");
        } else {
            relativeUrl = element.select(GIVEAWAY_THUMBNAIL_MISSING_CLASS).attr("href");
        }

        int pointCost = 0;
        Element pointElement = element.select(GIVEAWAY_MISC_CLASS).last();
        if (pointElement != null) {
            pointCost = Integer.parseInt(pointElement.text().replaceAll(NOT_NUMBER_REGEX, ""));
        }

        return new Giveaway(title, relativeUrl, pointCost);
    }

    private Document getDocumentFromUrl(String url) {
        Document document = null;

        try {
            document = Jsoup.connect(url).cookie(requestsFileContent.getCookieName(),
                    requestsFileContent.getCookieValue()).get();
        } catch (IOException exception) {
            exception.printStackTrace();
        }

        return document;
    }

    private int getRemainingPoints() {
        Document document = getDocumentFromUrl(BASE_URL);
        if (document == null) return 0;
        return Integer.parseInt(document.select(NAV_POINTS_CLASS).text());
    }

    private String[] scrapeLinksToEnteredGiveaways() {
        List<String> links = new ArrayList<>();
        int pageNumber = 1;
        boolean hasMore = true;

        do {
            Document document = getDocumentFromUrl(ENTERED_GIVEAWAYS_SEARCH_URL + pageNumber);
            if (document != null) {
                Elements elements = document.select(TABLE_ROW_INNER_WRAP_CLASS);

                for (Element element : elements) {
                    if (element.select(TABLE_COLUMN_SECONDARY_LINK_CLASS).isEmpty()) {
                        if (element == elements.last()) {
                            hasMore = false;
                            break;
                        }
                    } else {
                        links.add(element.select(TABLE_COLUMN_HEADING_CLASS).attr("href"));
                    }
                }

                pageNumber++;
            } else {
                hasMore = false;
            }
        } while (hasMore);

        return links.toArray(new String[0]);
    }

    private boolean enterGiveaway(Giveaway giveaway) {
        try {
            String body = "xsrf_token=" + requestsFileContent.getXsrfToken() + "&do=entry_insert&code=" +
                    giveaway.getGiveawayCode();

            Document document = Jsoup.connect(AJAX_REQUEST_URL).referrer(BASE_URL + giveaway.getRelativeUrl())
                    .cookie(requestsFileContent.getCookieName(), requestsFileContent.getCookieValue())
                    .requestBody(body).ignoreContentType(true).post();

            String response = document.text();

            for (String element : SUCCESS_KEYWORDS) {
                if (!response.contains(element)) {
                    return false;
                }
            }

            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public void enterGiveaways(Giveaway[] giveaways) {
        List<String> linksToEnteredGiveaways = Arrays.asList(scrapeLinksToEnteredGiveaways());
        List<Giveaway> notEnteredGiveaways = new ArrayList<>();

        Utils.printFoundEnteredGiveaways(linksToEnteredGiveaways.size());

        for (Giveaway giveaway : giveaways) {
            if (!linksToEnteredGiveaways.contains(giveaway.getRelativeUrl())) {
                notEnteredGiveaways.add(giveaway);
            }
        }

        Utils.printFoundGiveawayCandidates(notEnteredGiveaways.size());

        ExecutorService threadPool = Executors.newCachedThreadPool();
        List<CompletableFuture<Giveaway>> futures = new ArrayList<>();
        for (Giveaway giveaway : notEnteredGiveaways) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                if (enterGiveaway(giveaway)) {
                    Utils.printEnteredGiveaway(giveaway.getTitle());
                    return giveaway;
                } else {
                    Utils.printFailedToEnterGiveaway(giveaway.getTitle());
                    return null;
                }
            }, threadPool));
        }

        int pointsSpent = 0;
        int enteredGiveaways = 0;
        for (CompletableFuture<Giveaway> future : futures) {
            try {
                Giveaway giveaway = future.get();
                if (giveaway != null) {
                    pointsSpent += giveaway.getPointCost();
                    enteredGiveaways++;
                }
            } catch (ExecutionException | InterruptedException exception) {
                exception.printStackTrace();
                Thread.currentThread().interrupt();
            }
        }

        Utils.printFinalSummary(enteredGiveaways, pointsSpent, getRemainingPoints());

        threadPool.shutdownNow();
    }

    public String[] scrapeTitlesOfAllEnteredGiveaways() {
        Set<String> titles = new HashSet<>();
        Document document = getDocumentFromUrl(ENTERED_GIVEAWAYS_URL);

        if (document == null) return new String[]{};

        Element lastDataPageNumberElement = document.select("[data-page-number]").last();

        if (lastDataPageNumberElement == null) return new String[]{};

        int pageCount = Integer.parseInt(lastDataPageNumberElement.attr("data-page-number"));
        ExecutorService threadPool = Executors.newCachedThreadPool();
        List<CompletableFuture<String[]>> futures = new ArrayList<>();
        for (int i = 1; i <= pageCount; i++) {
            int finalI = i;
            futures.add(CompletableFuture.supplyAsync(() -> {
                Document searchDocument = getDocumentFromUrl(ENTERED_GIVEAWAYS_SEARCH_URL + finalI);
                if (searchDocument == null) return new String[]{};
                Elements elements = searchDocument.select(TABLE_COLUMN_HEADING_CLASS);
                List<String> giveawayTitles = new ArrayList<>();
                for (Element element : elements) {
                    giveawayTitles.add(element.text());
                }
                return giveawayTitles.toArray(new String[0]);
            }, threadPool));
        }

        for (CompletableFuture<String[]> future : futures) {
            try {
                String[] titlesOnPage = future.get();
                for (int i = 0; i < titlesOnPage.length; i++) {
                    titlesOnPage[i] = titlesOnPage[i].replaceAll(" \\(\\d+ Copies\\)", "");
                }
                Collections.addAll(titles, titlesOnPage);
            } catch (ExecutionException | InterruptedException exception) {
                exception.printStackTrace();
                Thread.currentThread().interrupt();
            }
        }

        threadPool.shutdownNow();
        return titles.toArray(new String[0]);
    }
}
