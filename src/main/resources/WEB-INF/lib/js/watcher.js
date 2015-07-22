const getData = function (act) {
    const x = new XMLHttpRequest();
    x.open("GET", "../data", true);
    x.onreadystatechange = function () {
        if (x.readyState == 4) {
            if (x.status == 200) {
                act(x.responseText);
            }
        }
    };
    x.send();
};
const handler = function () {
    getData(function (x) {
        document.getElementById("diagram").innerHTML = Viz(x, "svg");
    });
};
handler();
window.setInterval(handler, 1000);
