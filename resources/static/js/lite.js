//CONFIG
var FEE_ADDRESS = "1CHACHAGuuxTr8Yo9b9SQmUGLg9X5iSeKX";
var PREFIX = 'CHANCECO';
var ID_ROLL = 14;
var ID_DICE = 40;
var ID_POKER = 41;
var LENGTH_ROLL = 40;
var LENGTH_ROLL2 = 48;
var LENGTH_DICE = 24;
var LENGTH_POKER = 30;
var DUST_SIZE = 780;
var MIN_FEE = 1000;
var FEE_ADDRESS_FEE = 3560;
var UNIT = 100000000;
var MAX_PROFIT = 0.01;
var HOUSE_EDGE = 0.01;

$(window).on('load', function () {
    $('.selectpicker').selectpicker({
        'selectedText': 'cat'
    });
});

$(document).ready(function() {
  if(location.hash) {
    $('a[href=' + location.hash + ']').tab('show');
  }
  $(document.body).on("click", "a[data-toggle]", function(event) {
    location.hash = this.getAttribute("href");
  });
  if($("#num_unresolved_bets").html() > 0) {
    setInterval(function(){updateUnresolvedBets();}, 5000);
  }
  setInterval(function(){update();}, 5000);

  //test
  // var address = readCookie("address");
  // createDiceBet(0.01, "instant", "CHA", 1, 99, address);
});

$(window).on('popstate', function() {
  var anchor = location.hash || $("a[data-toggle=tab]").first().attr("href");
  $('a[href=' + anchor + ']').tab('show');
});

function updateChatStatus() {
  $.ajax({
    url: "/chat_status_update?chat_open="+$('#chat_open').html(),
    type: 'GET',
    success: function(data) {
    }
  });
}

function updateUnresolvedBets() {
  $.ajax({
    type: "POST",
    url: "/update_unresolved_bets",
    success: function(response) {
      if (response < $("#num_unresolved_bets").html()) {
        window.location.reload(true);
      } else {
        $("#num_unresolved_bets").html(response);
      }
    }
  });
}

function update() {
  //getPendingBets();
}

function importPrivateKey() {
  var privateKey = $( "input[name=privatekey]" ).val();
  if (privateKey) {
      try {
          var key = new Bitcoin.ECKey.fromWIF(privateKey);
          var address = key.pub.getAddress().toString();
          var addresses = JSON.parse(readCookie("addresses"));
          var privateKeys = JSON.parse(readCookie("private_keys"));
          if (addresses == null) {
            addresses = [];
          }
          if (addresses.indexOf(address) < 0) {
            addresses.push(address);
          }
          if (privateKeys == null) {
            privateKeys = [];
          }
          if (privateKeys.indexOf(privateKey) < 0) {
            privateKeys.push(privateKey);
          }
          eraseCookie("address");
          eraseCookie("addresses");
          eraseCookie("private_key");
          eraseCookie("private_keys");
          createCookie("address", address, 999999);
          createCookie("addresses", JSON.stringify(addresses), 999999);
          createCookie("private_key", privateKey, 999999);
          createCookie("private_keys", JSON.stringify(privateKeys), 999999);
      } catch (e) {
      }
  } else {
      //eraseCookie("private_key");
      //eraseCookie("address");
  }
  getCasinoInfo();
}

//update the page using the address passed in
function updateAddress(newAddress) {
  var currentAddress = readCookie("address");
  console.log("new address:" + newAddress);
  console.log("current address:" + currentAddress);
  if (currentAddress == null || currentAddress == newAddress) {
    return;
  }
  var addresses = JSON.parse(readCookie("addresses"));
  var addressIndex = addresses.indexOf(newAddress);
  if (addressIndex >= 0) {
    var privateKeys = JSON.parse(readCookie("private_keys"));
    var newPrivateKey = privateKeys[addressIndex];
    //TODO: double check with Bitcoin.ECKey? to make sure the private key matches the address
    eraseCookie("address");
    eraseCookie("private_key");
    createCookie("address", newAddress, 999999);
    createCookie("private_key", newPrivateKey, 999999);
  }
}

//update the page using the address from URL
function updateAddressFromUrl() {
  var newAddress = getParameterByName("address");
  if (newAddress != null) {
    updateAddress(newAddress);
  }
}
function getParameterByName(name) {
    name = name.replace(/[\[]/, "\\[").replace(/[\]]/, "\\]");
    var regex = new RegExp("[\\?&]" + name + "=([^&#]*)"),
        results = regex.exec(location.search);
    return results === null ? "" : decodeURIComponent(results[1].replace(/\+/g, " "));
}
function updateAddressDropDown(addressInfos) {
  var addressSelected = readCookie("address");
  if (addressSelected) {
    $("#address").html(addressSelected.substring(0,6)+'... <b class="caret"></b>');
  }

  var listItems = $("#addresses").children();
  if (listItems) {
    for (var i in listItems) {
      if (i > 0) {
        listItems[i].remove();
      }
    }
  }

  if (addressInfos) {
    for (var i in addressInfos) {
      var addressInfo = addressInfos[i];
      var address = addressInfo["address"];
      var balanceCHA = addressInfo["balanceCHA"];
      if (address == addressSelected) {
        $("#addresses").append('<li id="address_'+i+'"><a href="?address='+address+'"><strong>'+ address+'</strong> <span class="badge"><span id="cha_balance_'+i+'">' + balanceCHA + '</span> CHA</span></a></li>');
      } else {
        $("#addresses").append('<li id="address_'+i+'"><a href="?address='+address+'">'+ address+' <span class="badge"><span id="cha_balance_'+i+'">' + balanceCHA + '</span> CHA</span></a></li>');
      }
    }
  }
}
function pushTx(txHex) {
    var url = "http://api.bitwatch.co/pushtx/"+txHex;
    var result = false;
    $.ajax({
      url: url,
      cache: false,
      async: false
    }).done(function( data ) {
      if (data.status == 200 || data.status == 201) {
        result = true;
      }
    });
    return result;
}
function getUnspents(address) {
    var url = "http://api.bitwatch.co/listunspent/"+address+"?verbose=1&minconf=0";
    console.log(url);
    var unspents = [];
    $.ajax({
      url: url,
      cache: false,
      async: false
    }).done(function( data ) {
      $.each(data.result, function(i,result){
          unspents.push(result);
      });
    });
    return unspents;
}
function getTransactions(address) {
    var url = "http://api.bitwatch.co/listtransactions/"+address+"?verbose=1&skip=0&count=100";
    var txs = [];
    $.ajax({
      url: url,
      cache: false,
      async: false
    }).done(function( data ) {
      $.each(data.result, function(i,result){
          txs.push(result);
      });
    });
    return txs;
}
function toFixed(value, precision) {
    var precision = precision || 0,
        power = Math.pow(10, precision),
        absValue = Math.abs(Math.round(value * power)),
        result = (value < 0 ? '-' : '') + String(Math.floor(absValue / power));
    if (precision > 0) {
        var fraction = String(absValue % power),
            padding = new Array(Math.max(precision - fraction.length, 0) + 1).join('0');
        result += '.' + padding + fraction;
    }
    return result;
}

function createDiceBet(bet, resolution, asset, chance, payout, address) {
  var chaSupply = chaSupplyForBetting();
  var balance = getBalance(address, "CHA");
  if (source=="") throwException("Please specify a source address.");
  if (!(bet>0)) throwException("Please bet more than zero.");
  if (!(chance>0.0 && chance<100.0)) throwException("Please specify a chance between 0 and 100.");
  if (!(payout>1.0)) throwException("Please specify a payout greater than 1.");
  if (!(toFixed(chance,6)==toFixed(100.0/(payout/(1.0-HOUSE_EDGE)),6))) throwException("Please specify a chance and payout that are congruent.");
  if (!(bet<=balance)) throwException("Please specify a bet that is smaller than your CHA balance.");
  if (!((payout-1.0)*bet<chaSupply*MAX_PROFIT)) throwException("Please specify a bet with a payout less than the maximum percentage of the house bankroll you can win.");

  btcAmount = 0;

  if (resolution == "instant") {
    destination = FEE_ADDRESS;
    btcAmount = FEE_ADDRESS_FEE;
  }
  if (asset=="BTC") {
    destination = FEE_ADDRESS;
    btcAmount = FEE_ADDRESS_FEE + bet;
    bet = 0;
  }

  jsp = new JSPack();
  var byteBuffer = jsp.Pack(">8sIQdd", [PREFIX, ID_DICE, bet*UNIT, chance, payout]);

  var source = address;
  var destinations = [destination];
  var btcAmounts = [btcAmount];
  var fee = MIN_FEE;
  var data = byteBuffer;
  var useUnspentTxHash = "";
  var useUnspentVout = -1;
  var tx = createTransaction(source, destinations, btcAmounts, fee, data, useUnspentTxHash, useUnspentVout);
  var result = pushTx(tx.toHex());
  return result;
}

function processBet(formName) {
    var address = readCookie("address");
    var bet = $( "input[name=bet]" ).val();
    var resolution = $( "input[name=resolution]" ).val();
    var asset = $( "select[name=asset]" ).val();
    var chance = $( "input[name=chance]" ).val();
    var payout = $( "input[name=payout]" ).val();
    if (formName=="dice" && bet && resolution && asset && chance && payout && address) {
      var result = createDiceBet(bet, resolution, asset, chance, payout, address);
      if (result) {
        alert("Thanks for betting!");
      }
    }
}
function createTransaction(source, destinations, btcAmounts, fee, data, useUnspentTxHash, useUnspentVout) {
  tx = new Bitcoin.Transaction();
  var address = readCookie("address");
  var private_key = readCookie("private_key");
  if (destinations.length>0 && btcAmounts.length==destinations.length) {
    var destination = destinations[0];
    var btcAmount = btcAmounts[0];
    if (destination=="" || btcAmount>=DUST_SIZE) {
      var totalOutput = fee;
      var totalInput = 0;
      for (i in destinations) {
        destination = destinations[i];
        btcAmount = btcAmounts[i];
        if (destination!="" && btcAmount>0) {
          totalOutput += btcAmount;
          tx.addOutput(destination, btcAmount);
        }
      }
      for (i = 0; i<data.length; i+=32) {
        var chunk = data.slice(i, Math.min(i+32, data.length));
        chunk.splice(0,0,chunk.length);
        while (chunk.length<=32) {
          chunk.push(0);
        }
        var keys = [];
        if (source == address) {
          keys.push(Bitcoin.ECKey.fromWIF(private_key));
        }
        var pubKeys = keys.map(function(x) { return x.pub });
        var pubKeyBuffers = keys.map(function(x) { return x.pub.toBuffer() });
        pubKeyBuffers.push(chunk);
        var redeemScript = new Bitcoin.scripts.multisigOutputFromPubKeyBuffers(1, pubKeyBuffers);
        var scriptPubKey = new Bitcoin.scripts.scriptHashOutput(redeemScript.getHash());
        var multisigAddress = new Bitcoin.Address.fromOutputScript(scriptPubKey).toString();
        tx.addOutput(redeemScript, DUST_SIZE);
				totalOutput = totalOutput + DUST_SIZE;
      }

      var unspents = getUnspents(source);
      var inputKeys = [];
      var inputScripts = [];
      var inputTypes = [];
      var atLeastOneRegularInput = false;
      for (i in unspents) {
        var unspent = unspents[i];
        if ((useUnspentTxHash==unspent.txid && useUnspentVout==unspent.vout) || (useUnspentVout<0 && ((unspent.type=="pubkeyhash" && (totalOutput>totalInput || !atLeastOneRegularInput)) || (unspent.type=="multisig")))) {
          if (unspent.type=="pubkeyhash") {
            atLeastOneRegularInput = true;
          }
          if (source == address) {
            totalInput = totalInput + unspent.amount*UNIT;
            tx.addInput(unspent.txid, unspent.vout);
            inputScripts.push(Bitcoin.Script.fromHex(unspent.scriptPubKey.hex));
            inputKeys.push(Bitcoin.ECKey.fromWIF(private_key));
            inputTypes.push(unspent.type);
          }
        }
      }

      if (!atLeastOneRegularInput) {
				throwException("Not enough standard unspent outputs to cover transaction.");
			}

			if (totalInput<totalOutput) {
				throwException("Not enough BTC to cover transaction.");
			}
			var totalChange = totalInput - totalOutput;

			if (totalChange>0) {
				tx.addOutput(source, totalChange);
			}

      key = new Bitcoin.ECKey.fromWIF(private_key);
      var pubKeys = inputKeys.map(function(eck) { return eck.pub })
      for (i in tx.ins) {
        var signature = tx.signInput(i, inputScripts[i], inputKeys[i]);
        if (inputTypes[i] == "multisig") {
          var redeemScriptSig = new Bitcoin.scripts.multisigInput([signature]);
          //var scriptSig = new Bitcoin.scripts.scriptHashInput(redeemScriptSig, inputScripts[i]);
          tx.setInputScript(i, redeemScriptSig);
        } else if (inputTypes[i] == "pubkeyhash") {
          var redeemScriptSig = new Bitcoin.scripts.pubKeyHashInput(signature, inputKeys[i].pub);
          tx.setInputScript(i, redeemScriptSig);
        }
      }
      return tx;
    }
  }
}
function getTx(txid) {
    var url = "https://insight.bitpay.com/api/tx/"+txid;
    var tx = {};
    $.ajax({
      url: url,
      cache: false,
      async: false
    }).done(function( data ) {
      tx = data;
    });
    return tx;
}
function createHexString(arr) {
    var result = "";
    var z;
    var len = 2;
    for (var i = 0; i < arr.length; i++) {
        var str = arr[i].toString(16);
        z = len - str.length + 1;
        str = Array(z).join("0") + str;
        result += str;
    }
    return result;
}
function parseHexString(str) {
    var result = [];
    var len = 2;
    while (str.length >= len) {
        result.push(parseInt(str.substring(0, len), 16));
        str = str.substring(len, str.length);
    }
    return result;
}
function getMessage(data) {
  return data.slice(4,data.length);
}
function getMessageType(data) {
  return data.slice(0,4);
}
function getBets() {

}
function getPendingBets() {
  var unspents = getUnspents(FEE_ADDRESS);
  for (i in unspents) {
    var unspent = unspents[i];
    if (unspent.confirmations==0) {
      var tx = getTx(unspent.txid);
      var data = [];
      for (vout in tx.vout) {
        var vout = tx.vout[vout];
        var asm = vout.scriptPubKey.asm.split(' ');
        if (asm.length==2 && asm[0]=="OP_RETURN") {
          data = data.concat(parseHexString(asm[1]));
        } else if (asm.length>=5 && asm[0]=='1' && asm[3]=='2' && asm[4]=='OP_CHECKMULTISIG') {
          var data_pubkey = parseHexString(asm[2]);
          var data_chunk_length = data_pubkey[0];
          data = data.concat(data_pubkey.slice(1,data_chunk_length+1));
        }
      }
      var prefix_bytes = PREFIX.split ('').map (function (c) { return c.charCodeAt (0); });
      if (JSON.stringify(data.slice(0,prefix_bytes.length))==JSON.stringify(prefix_bytes)) {
        data = data.slice(prefix_bytes.length,data.length);
        var message = getMessage(data);
        var messageType = getMessageType(data)[3];
        if (messageType==ID_ROLL && (message.length==LENGTH_ROLL || message.length==LENGTH_ROLL2)) {
          //console.log(message);
          jsp = new JSPack();
          var txhash = createHexString(message.slice(0,32));
          //console.log(txhash);
          //console.log(jsp.Unpack("32sd",message));
        }
      }
    }
  }
}

function getCardRank(card) {
  if (card.charAt(0) == "T") {
    return 10;
  } else {
    return card.charAt(0);
  }
}
function getCardSuit(card) {
  if (card.charAt(1) == "D") {
    return "diams";
  } else if (card.charAt(1) == "H") {
    return "hearts";
  } else if (card.charAt(1) == "S") {
    return "spades";
  } else if (card.charAt(1) == "C") {
    return "clubs";
  }
}

function createCookie(name,value,days) {
    if (days) {
        var date = new Date();
        date.setTime(date.getTime()+(days*24*60*60*1000));
        var expires = "; expires="+date.toGMTString();
    }
    else var expires = "";
    document.cookie = name+"="+value+expires+"; path=/";
}
function readCookie(name) {
    var nameEQ = name + "=";
    var ca = document.cookie.split(';');
    for(var i=0;i < ca.length;i++) {
        var c = ca[i];
        while (c.charAt(0)==' ') c = c.substring(1,c.length);
        if (c.indexOf(nameEQ) == 0) return c.substring(nameEQ.length,c.length);
    }
    return null;
}
function eraseCookie(name) {
    createCookie(name,"",-1);
}
function throwException(error) {
    alert(error);
    throw error;
}
function getCasinoInfo() {
  $.ajax({
    type: "GET",
    url: "http://0.0.0.0:8080/get_casino_info",
    data: {addresses: readCookie("addresses"), address: readCookie("address")},
    crossDomain: true,
    success: function(response) {
      var responseObj = JSON.parse(response);
      console.log("address from ajax response: " + responseObj.address);
      updateAddressDropDown(responseObj.addressInfos);

      $("#cha_price_dollar").html("1 CHA = $" + (responseObj.price_BTC *responseObj.price_CHA).toFixed(2));
      $("#cha_supply").html(responseObj.supply.toLocaleString());
      $("#cha_price").html(responseObj.price_CHA.toFixed(4) + " BTC");
      $("#btc_price").html("$"+responseObj.price_BTC.toFixed(2));
      $("#market_cap").html("$"+(responseObj.supply * responseObj.price_BTC * responseObj.price_CHA).toLocaleString());

      var currentChaBlocks;
      if (responseObj.parsing) {
        currentChaBlocks = responseObj.parsing;
      } else {
        currentChaBlocks = responseObj.blocksCHA;
      }
      $("#cha_over_btc_blocks").html(currentChaBlocks.toLocaleString() + " / " + responseObj.blocksBTC.toLocaleString());
      $("#cha_blocks").html(currentChaBlocks.toLocaleString());
      $("#btc_blocks").html(responseObj.blocksBTC.toLocaleString());
      $("#version").html(responseObj.version);

      $("#recent_bets_content").html(getBetTableHtml(responseObj.bets));
      if (responseObj.address) {
        $("#my_bets_content").html(getBetTableHtml(responseObj.my_bets));
      }
    }
  });
}

function getBetTableHtml(betObjects) {
  var html = "";
  html += "<table class='table table-striped'>"
  html += "<thead>";
  html +=	"<tr>";
  html +=	"<th>Source address</th>";
  html +=	"<th>Time</th>";
  html +=	"<th>Bet size</th>";
  html +=	"<th>Chance to win / payout multiplier</th>";
  html +=	"<th>Result</th>";
  html +=	"<th>Profit</th>";
  html +=	"</tr>";
  html +=	"</thead>";
  html += "<tbody>";
  for (var i = 0; i < betObjects.length; i++) {
    var betInfo = betObjects[i];
    html += "<tr>";
    html += "<td>"+betInfo["source"].substring(0,6)+"...</td>";
    html += "<td>"+betInfo["block_time"]+"</td>";
    html += "<td>"+betInfo["bet"]+" CHA</td>";
    html += "<td>"+parseFloat(betInfo["chance"].toPrecision(3))+"%"+" / "+parseFloat(betInfo["payout"].toPrecision(3))+"X</td>";
    //html += "<td>"+parseFloat(betInfo["chance"])+"%"+" / "+parseFloat(betInfo["payout"])+"X</td>";

    if (betInfo["cards"]) {
      html += "<td>";
      var cardArray = betInfo["cards"].split(" ");
      for (var cardIndex = 0; cardIndex < cardArray.length; cardIndex++) {
          if (cardIndex == 0) {
              html += "<div style='float: left; padding-top: 1.25em; padding-right: 2.5em;'>Player</div>";
          }
          if (cardIndex == 7) {
              html += "<div style='float: left; padding-top: 1.25em; padding-right: 1em;'>Opponent</div>";
          }
          if (cardArray[cardIndex] == "??") {
              html += "<div class='card back'>*</div>";
          } else {
              html += "<div class='card rank-"+getCardRank(cardArray[cardIndex])+" "+getCardSuit(cardArray[cardIndex])+"'>";
              html += "<span class='rank'>"+getCardRank(cardArray[cardIndex])+"</span>";
              html +=	"<span class='suit'>&"+getCardSuit(cardArray[cardIndex])+";</span>";
              html += "</div>";
          }
          if (cardIndex==1 || cardIndex==6) {
              html += "<div style='clear: both;'></div>";
          }
      }
      if (betInfo["cards_result"]) {
          html += "<p>"+betInfo['cards_result']+"</p>";
      }
      html += "</td>";
    } else {
      html += "<td><img src='http://chancecoin.com/images/dice.png' style='height: 25px; display: inline;' />";
      if (betInfo["resolved"]) {
        html += parseFloat(betInfo["roll"].toPrecision(5));
      } else {
        html += "?";
      }
      html += "</td>";
    }
    html += "<td>";
    if (betInfo["resolved"] && betInfo["resolved"]=="true") {
      html += betInfo["profit"]+" CHA";
    } else {
      html += "<img src='http://chancecoin.com/images/ajax-loader.gif' /></td>";
    }
    html += "</tr>";
  }
  html += "</tbody>";
  html += "</table>";
  return html;
}
function chaSupplyForBetting() {
  var url = "http://0.0.0.0:8080/cha_supply_for_betting";
  var result = 0;
  $.ajax({
    url: url,
    cache: false,
    async: false
  }).done(function( data ) {
    result = data;
  });
  return result;
}
function getBalance(address, asset) {
  var url = "http://0.0.0.0:8080/get_balance_by_asset";
  var result = 0;
  $.ajax({
    url: url,
    data: {"address": address, "asset": asset},
    cache: false,
    async: false
  }).done(function( data ) {
    result = data;
  });
  return result;
}
