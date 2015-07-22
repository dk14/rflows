declare var Viz

const getData = (act: (any) => void) => {

    const x = new XMLHttpRequest()
    x.open("GET", "../data", true)
    x.onreadystatechange = () => {
      if (x.readyState == 4) {
        if (x.status == 200) {
          act(x.responseText)
        }
      }
    }
    x.send()
}

const handler = () => {
  getData(x => {document.getElementById("diagram").innerHTML = Viz(x, "svg")})
}

handler()
window.setInterval(handler, 1000)



